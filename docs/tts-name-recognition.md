# Chinese name recognition

The built-in NER path (algorithm 101) runs the Chinese model through the existing
source-built ONNX Runtime. `SpeechNameRecognizer` is shared by the playback-settings
test and future text consumers. It returns candidates only; it does not create
characters or voice pairs. Body discovery and pairing belong to #785.

The model and vocabulary come from MultiTTS 1.8.3 (84), APK SHA-256
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`:

| Asset | SHA-256 |
| --- | --- |
| `ner_cn_model.onnx` | `df1b43fa9f3709e7abfed388ab34add337a202f424aff6f2819a027b4a043f7c` |
| `cn_vocab.txt` | `96cb2e948a2d4aa2252f11de0da072c7ea22bd5234ac0f3bdcd028355d808fbc` |

The original closed `libngr.so` is not shipped or called. Maintained Kotlin code
implements vocabulary encoding, punctuation-aware windows, Viterbi decoding and
UTF-16 range mapping. The JNI adapter only loads the model, reads its transition
metadata and executes inference. The ordinary arm64/API 26+ backend build includes
it; other devices show the existing runtime requirement.

## Compatibility evidence

REA Android inspection confirms `Ner.a` trims Java whitespace and returns names
without ranges (`ev_20c9399eb6a7839b9b5bfa8fe2097674fedde0a1c507c97e64b8a44dad400cb9`).
REA Rizin inspection of the shipped library confirms the 30-slot encoding,
punctuation splitting and full padded-sequence Viterbi decoder
(`ev_36f5c4ebac88f70bbba59003ead6075aa01c7a88c991ec318ec4814fd82122a6`,
`ev_75ad22dcd85324d538e98de12cee2a774ca07b3aa1cd20b1a7d19227ba8f180b`,
`ev_4a813a97007c65ce2a0cc133c27a83cfac01abe186ccddc7b35aac458c2077e2`).
Supplemental Ghidra pseudocode was checked against those assembly observations.

The original JNI widens each UTF-16 unit, including individual surrogate halves
(`ev_fbeb7be27481b886dcc220596e9247ca7fdd375d78dc449f35feb82a82d2959a`).
This behavior is preserved. The model can include emoji in a candidate and can
truncate names at a window boundary. Candidates are predictions, not confirmed
characters, and the model is not described as supporting every language.

Controlled REA probes of the original app covered 22 cases, including nicknames,
ordinary words, repeated names, whitespace, supplementary characters and window
boundaries. A recovered reference runner matched all 22 results
(`ev_748eed8557d7a28c054adf719626744e9388160d2beee9c915bd64cd165bd055`,
`ev_97947c615fa011d22b1600c477f93f62c4474bc49719f2d7bbb56ce89f573525`).
The small JVM fixture retains model emissions and independently observed names;
the instrumented test executes the bundled model on supported devices.

An isolated MuMu probe compiled the maintained `name_model.cc` and `NerDecoder.kt`
with the existing source-built ORT. Its 35 inference windows reproduced all 22
original name lists and all 22 independently mapped UTF-16 span results. This
checks the delivered inference/decoder sources separately from the Python reference.

## Lifetime and mapping

Each request reads the bundled resources and owns its model session until `finally`
releases it. No candidate cache or shared book state is kept. Resource revisions
therefore cannot reuse old results. Cancellation is checked before model loading,
between bounded 30-slot inferences and before publishing results. Editing, leaving
or cancelling the test discards its pending result.

Ranges are reconstructed from decoded spans, not by searching for the first
matching name. Repeated names retain distinct ranges. The `SpeechMappedText`
overload composes these spans with the existing replacement/original-text mapping.
