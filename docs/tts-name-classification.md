# Name classification for voice selection

`SpeechNameClassifier.classify` returns a male/female/unknown voice-selection hint
and its basis (model, male rule, female rule, or none). It does not create or update
characters, voice pools or pairs. #785 consumes this result after existing/manual
bindings; #809 uses the same contract for text analysis. The labels are model
predictions about names, not assertions about a character's attributes.

The model runs through the existing source-built ONNX Runtime and maintained JNI
adapter. The original closed `libngr.so` is neither shipped nor called.

## Model and resources

Assets come from MultiTTS 1.8.3 (84), APK SHA-256
`d3f7736b1bf162829677c0772a344696f8f5940e99f45879aa5e26070d0416af`.

| Asset | SHA-256 |
| --- | --- |
| `ngr_cn_model.onnx` | `64c05315508c6a1d887504d77737a7044375e10646520cbae5fc912a521e1b38` |
| `ngr_cn_word.txt` | `84e131d5439b3eeb86b0eb2d6c78807f36888aa65b6b660d47d80028bafd34f8` |
| `ngr_families.txt` | `ee7b7d95d613db83e26453e538a24cae0295bd09843d43c4600f0b280aedce5a` |

The first two files retain their original bytes. `ngr_families.txt` is the exact
compiled surname array recovered from the library's initializer: 1,764 entries,
1,758 unique, including one-, two- and three-character surnames. It is data, not a
replacement name-recognition algorithm.

`NgrEncoder` follows the original `get_first_name` and `word2vector` functions:

1. Remove the longest matching surname prefix (up to three characters), leaving
   at least one character. Keep the last three characters of the remaining name.
2. Look up three individual-character scores, then two adjacent-bigram scores.
   Missing positions/keys are zero. The vocabulary contains 149,307 token/float32
   scores; a known token with score zero still constitutes supported input.
3. Run `input_float` with shape `[1,5]` and read `output_label`, int64 `[1]`.
   As in MultiTTS, zero selects the female hint and nonzero the male hint. The
   original does not consume the model's probability output or apply a confidence
   threshold, and neither does this implementation.

JNI widens Java UTF-16 units individually rather than combining surrogate pairs.
Kotlin String/Char indexing preserves this behavior, including unknown surrogate
halves. The model is trained for Chinese names; recognition of a character or
token does not establish support for another language.

## Rules and explicit compatibility differences

REA confirms the original first returns existing voice pairs, then uses Ngr when
its UI locale allows Chinese inference. Its non-Chinese fallback checks the male
regex with a full match and otherwise chooses the female pool. The female regex
is compiled but discarded: raw smali contains no `move-result`/`sput` after its
`Pattern.compile`. The male Pattern is also created only once, so later preference
changes do not rebuild it.

This implementation preserves model priority. If no vocabulary token matches, or
the device has no supported native runtime, it checks the male rule, then the
female rule, using full matches. Male wins when both rules match. The defaults are
empty. Rules are evaluated from the current request; invalid expressions surface
as errors when that rule path is used.

The following differences are explicit:

- Retaining and using the female pattern fixes the confirmed original defect;
  current rule edits take effect immediately instead of retaining an old Pattern.
- Model support follows actual vocabulary coverage and runtime availability,
  rather than the app's interface language.
- No matching rule/evidence returns Unknown, as required by #825. The original
  forces a binary result: for example, an all-zero unknown-input vector predicts
  male. Unknown is a NextVol contract, not an original JNI return value.

On a model-capable device, missing/corrupt resources or an inference failure remain
errors; they are not silently converted to Unknown. `available` describes model
availability only. Rule-based classification remains usable on other devices.

## Evidence and validation

REA evidence establishes the Java routing and native implementation:

- Pair/model/rule order: `ev_ae86be4bbac1a67e69f8cb2b8c8badedac77d41d098b34a22c4a52a817b1cfdc`.
- Discarded female regex in raw smali: `ev_dfaa217732f0f8930f435f507f7a343484004432e23ec39badaef0d809f35a55`.
- Surname processing: `ev_b97ddf72118100c3c7c09de6be7678bc78f6610229098752cb05088d8d314202`.
- Five feature positions: `ev_98a05e36d3279b4fc02e4f74b5c2a4b2ae6fd9ef53f6480539faae57f5fc2efb`.
- Native input/output: `ev_bd7efe9f8eeff38f30a04878cca1945cb84566df866c1e4de3aaa46eada7e83c`.
- UTF-16 widening: `ev_fbeb7be27481b886dcc220596e9247ca7fdd375d78dc449f35feb82a82d2959a`.
- Surname initialization and insertion loop: `ev_0cf8fcff44ad2eb8fea0540bb352bf205b5ad0bbcbf6de631556b9c1a75def95`,
  `ev_fa34799d7b405089e91b35440eff8a874a5d269b1e138a2726a04a0d4c2785bb`.

A controlled REA probe executed the original Ngr implementation
(`ev_7c04ad5115e68cca3de5c94f69ccd95b8a2241f86d8e82be9f68741aa3ec33ae`).
王小明 and 司马光 returned male; 林黛玉, 欧阳娜娜 and 赵敏 returned female.
贾宝玉 also returned female, while ordinary words 天气 and 桌子 returned male.
These limitations are preserved rather than hidden behind invented heuristics.
The reconstructed inputs and shipped model also ran on host ONNX Runtime with
matching labels for these cases.

An isolated MuMu probe compiled the maintained `NgrEncoder.kt` and `name_model.cc`
with the source-built runtime. All 12 original labels and all 12 independently
reconstructed feature vectors matched. Unknown Latin/emoji inputs retained the
original raw model label while correctly reporting no vocabulary support for the
NextVol fallback contract.

Focused JVM tests cover surname/feature positions, UTF-16 unknown units, known
zero scores, changed rules, full matches and conflict order. The instrumented
test executes the bundled model and checks its priority over fallback rules.

## Ownership and cancellation

Each request reads the packaged resources and owns its model session until a
`finally` block releases it. There is no shared result cache or book state, so
resource revisions and rule edits cannot reuse stale classifications. Cancellation
is checked after preparation, after model loading and before returning inference
results; a cancelled inference finishes its single bounded five-feature call and
releases the handle without publishing a result.
