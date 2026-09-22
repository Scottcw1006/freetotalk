# freetotalk

Android 上的離線 LLM 聊天 App，模型用 MediaPipe LLM Inference 在裝置上跑，不連網。功能清單見 `docs/product/features.md`。

## 模型檔：clone 之後要自己放

三個模型檔加起來 1.7 GB，單檔就超過 GitHub 的 100 MB 上限，所以 **不在 repo 裡**（`.gitignore` 的 `app/src/main/assets/*.task` 擋掉了）。沒放模型直接 build，App 裝得起來但沒有任何模型可以聊。

### 來源與放置路徑

三個檔都來自 Hugging Face 的 `litert-community`，下載後 **改成下面的檔名**，放進 `app/src/main/assets/`：

| App 裡的名稱 | 來源 repo | 下載這個檔 | 改名為 | 大小（bytes） |
|---|---|---|---|---|
| Gemma3 1B（預設） | [litert-community/Gemma3-1B-IT](https://huggingface.co/litert-community/Gemma3-1B-IT) | `Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task` | `gemma3-1b.task` | 1,054,012,582 |
| Qwen2.5 0.5B | [litert-community/Qwen2.5-0.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct) | `Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task` | `model.task` | 546,660,344 |
| SmolLM 135M | [litert-community/SmolLM-135M-Instruct](https://huggingface.co/litert-community/SmolLM-135M-Instruct) | `SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task` | `smollm.task` | 166,754,726 |

放好之後的結構：

```
app/src/main/assets/
├── gemma3-1b.task
├── model.task
└── smollm.task
```

### 注意

- **Gemma3 1B 的 repo 是 gated**：要先登入 Hugging Face、在該頁面同意 Google 的 Gemma 使用條款，才能下載。另外兩個不用登入。
- **一定要選 `q8_ekv1280` 那個版本。** 程式碼（`app/src/main/java/com/example/demo/llm/ModelSpec.kt`）把 KV cache 寫死為 1280，拿 `ekv2048`、`ekv4096` 或 `q4` 的版本會在載入模型時失敗。
- 下載完可以比對上表的 bytes 數確認檔案完整。
- 只放其中一、兩個也能跑，App 只會在抽屜裡列出實際存在的模型；但預設模型是 Gemma3 1B，建議三個都放。

### 用指令下載

```bash
pip install -U "huggingface_hub[cli]"
huggingface-cli login   # 只有 Gemma 需要；先在網頁上同意條款
cd app/src/main/assets
huggingface-cli download litert-community/Gemma3-1B-IT Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task --local-dir . && mv Gemma3-1B-IT_multi-prefill-seq_q8_ekv1280.task gemma3-1b.task
huggingface-cli download litert-community/Qwen2.5-0.5B-Instruct Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task --local-dir . && mv Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task model.task
huggingface-cli download litert-community/SmolLM-135M-Instruct SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task --local-dir . && mv SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task smollm.task
```

## 開發流程

規格、任務與驗收報告在 `docs/specs/<功能名稱>/`，用 PM / RD / QA 三角色的 `/spec-flow` 流程產出。QA 環境的準備方式在 `docs/qa-environment.md`。
