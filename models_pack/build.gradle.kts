// The Play flavour's models (Whisper, the stem separator) as an install-time asset pack, so the
// bundle Play accepts stays under its size limit while the app still opens with the models on the
// phone. The `full` flavour carries the same files in app/src/full/assets instead. The files are
// git-ignored; `sh tools/models/fetch_models.sh play` puts them here.
plugins {
    id("com.android.asset-pack")
}

assetPack {
    packName.set("models")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}
