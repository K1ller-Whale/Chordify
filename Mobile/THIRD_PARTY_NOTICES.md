# Third-party notices

Chordify Mobile is GPL-3.0-or-later ([`LICENSE`](LICENSE)). It includes, or links to, the
software below. The app's About screen shows the same list.

| Component | Licence | Used for | Licence text |
|---|---|---|---|
| NNLS Chroma (c4dm, commit 4c5f214) | GPL-2.0-or-later | The pitch features the chord model reads | [`engine/src/main/cpp/third_party/nnls-chroma/COPYING`](engine/src/main/cpp/third_party/nnls-chroma/COPYING) |
| Vamp plugin SDK 2.6 | MIT-style | The plugin host that runs NNLS Chroma | The header of each file in [`engine/src/main/cpp/third_party/vamp-plugin-sdk/`](engine/src/main/cpp/third_party/vamp-plugin-sdk/) |
| libsoxr 0.1.3 | LGPL-2.1-or-later | Resampling audio to 44.1 kHz | [`engine/src/main/cpp/third_party/libsoxr/COPYING.LGPL`](engine/src/main/cpp/third_party/libsoxr/COPYING.LGPL) |
| ONNX Runtime for Android | MIT | Runs the chord model | https://github.com/microsoft/onnxruntime/blob/main/LICENSE |
| AndroidX, Jetpack Compose and Material 3 | Apache-2.0 | The screens, the library database and the background jobs | https://www.apache.org/licenses/LICENSE-2.0 |
| Media3 ExoPlayer | Apache-2.0 | Playback | https://www.apache.org/licenses/LICENSE-2.0 |
| Kotlin, kotlinx.coroutines and kotlinx.serialization | Apache-2.0 | The language, coroutines and JSON | https://www.apache.org/licenses/LICENSE-2.0 |

NNLS Chroma is GPL-2.0-or-later, so the app that bundles it is distributed under the GPL, version 3
or later. The native sources are unmodified upstream copies; see
[`engine/src/main/cpp/third_party/README.md`](engine/src/main/cpp/third_party/README.md).

## Models and data

The models are the repository's own, from `models/`:
- `chordnet-chroma@3.0.0`, the chord model. It was trained on McGill Billboard, POP909, GuitarSet (CC BY 4.0) and synthetic renders.
- `progression-ngram@1.0.0`, the progression model. It was built from the McGill Billboard chord annotations, read through ChoCo.

Each `bundle.json` records its training data and split hashes.
