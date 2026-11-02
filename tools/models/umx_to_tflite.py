#!/usr/bin/env python3
"""Open-Unmix (UMX, MIT) to four LiteRT models for Stave's StemsRunner.

The contract StemsRunner.kt assumes (keep the two in step):
    input  [1, T, 2, 2049] float32   magnitude spectrogram, STFT 4096 / hop 1024, stereo
    output [1, T, 2, 2049] float32   the stem's magnitude estimate for the same frames

UMX's own forward takes a magnitude spectrogram shaped (batch, channels, bins, frames) and
returns the same; this wraps each target model so the batch is first, exports it through
ai_edge_torch and quantises to int8 dynamic-range (about 9 MB a stem), then checks the
converted model against PyTorch on a random spectrogram.

    pip install -r tools/models/requirements-umx.txt
    python3 tools/models/umx_to_tflite.py app/src/full/assets/models

Runs on a desktop, once; the files are git-ignored and travel with the full flavour and the
asset pack. Licence: Open-Unmix code and weights are MIT (Stöter and Liutkus, Inria).
"""
import sys
from pathlib import Path

import numpy as np
import torch

# The converter was renamed LiteRT Torch (litert_torch); ai-edge-torch 0.4.0 is the last with
# convert() under the old name, and requirements-umx.txt pins it. Either is taken.
try:
    import litert_torch as ai_edge_torch
except ImportError:
    try:
        import ai_edge_torch
    except ImportError as e:
        sys.exit(f"ai-edge-torch will not import ({e!r}); pip install -r tools/models/requirements-umx.txt")
if not hasattr(ai_edge_torch, "convert"):
    sys.exit("this ai-edge-torch has no convert(); pin ai-edge-torch==0.4.0")

from openunmix import umxhq_spec  # the hq weights: 4096 FFT, 1024 hop, 44.1 kHz

T = 128  # frames per call (about 3 s); StemSeparator.WINDOW. XNNPACK packs the weights once per
         # unrolled LSTM step: at 256 the float vocals model held 1.7 GB on the phone.


class BatchFirst(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, x):            # x: [1, T, 2, 2049]
        # OpenUnmix.forward takes and returns (samples, channels, bins, frames) and crops to its
        # own bandwidth inside; the first wrapper fed it (frames, samples, channels, bins).
        y = self.model(x.permute(0, 2, 3, 1))   # [1, 2, 2049, T]
        return y.permute(0, 3, 1, 2)            # back to [1, T, 2, 2049]


def main(dest: str) -> None:
    out = Path(dest)
    out.mkdir(parents=True, exist_ok=True)
    models = umxhq_spec(targets=["vocals", "drums", "bass", "other"], pretrained=True)
    for name, umx in models.items():
        umx.eval()
        wrapped = BatchFirst(umx).eval()
        sample = (torch.rand(1, T, 2, 2049) * 2.0,)
        edge = ai_edge_torch.convert(wrapped, sample)
        float_path = out / f"umx_{name}.float.tflite"
        edge.export(str(float_path))
        path = out / f"umx_{name}.tflite"
        # Dynamic-range int8 weights (float in, float out, as the runner expects): about a
        # quarter of the size. Kept float if the quantiser is missing or the result strays.
        quantised = False
        try:
            from ai_edge_quantizer import quantizer, recipe
            qt = quantizer.Quantizer(str(float_path))
            qt.load_quantization_recipe(recipe.dynamic_wi8_afp32())
            qt.quantize().export_model(str(path))
            quantised = True
        except Exception as e:  # noqa: BLE001 - any failure leaves the float model
            print(f"{name}: not quantised ({e!r}); the float model ships")
        if not quantised:
            float_path.replace(path)
        # Check: the shipped model against PyTorch on the same input, through LiteRT itself.
        from ai_edge_litert.interpreter import Interpreter
        it = Interpreter(model_path=str(path))
        it.allocate_tensors()
        it.set_tensor(it.get_input_details()[0]["index"], sample[0].numpy())
        it.invoke()
        got = it.get_tensor(it.get_output_details()[0]["index"])
        with torch.no_grad():
            ref = wrapped(*sample).numpy()
        err = float(np.abs(got - ref).max() / (np.abs(ref).max() + 1e-9))
        print(f"{path.name}: {path.stat().st_size / 1e6:.1f} MB, {'int8 weights' if quantised else 'float'}, max relative error {err:.4f}")
        if quantised and err > 0.05:
            print(f"{name}: int8 strays by {err:.3f}; the float model ships instead")
            edge.export(str(path))
            it = Interpreter(model_path=str(path)); it.allocate_tensors()
            it.set_tensor(it.get_input_details()[0]["index"], sample[0].numpy()); it.invoke()
            got = it.get_tensor(it.get_output_details()[0]["index"])
            err = float(np.abs(got - ref).max() / (np.abs(ref).max() + 1e-9))
        if err > 0.05:
            sys.exit(f"{name}: the converted model disagrees with PyTorch by {err:.3f}; do not ship it")
        float_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/full/assets/models")
