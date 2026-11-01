Stave: an audio studio that cannot upload your sound.

Every tool is free. No account, no watermark, no cap, no subscription, no ads, and no INTERNET
permission in the manifest. The `.apk` carries every model inside it (`sh tools/models/fetch_models.sh` before the
build); the `-play.aab` is the Play bundle, whose models ride in the `models` install-time
asset pack (`sh tools/models/fetch_models.sh play`, module `models_pack`). The stem model is
made, not downloaded: `tools/models/umx_to_tflite.py` needs a desktop with Python.

See docs/FEATURES.md for what this version does and docs/PENDING_TESTS.md for what has been
built but not yet seen on a phone.
