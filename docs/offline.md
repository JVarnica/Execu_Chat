
Execu_Chat is an on-device android app which runs LLMs using Executorch. Why executorch? it is created by meta so you have a full end-to-end
system, which is consistently getting maintained. Furthermore you can delegate the exported graph for your desired backend (metal,gpu,vulkan,qnn).
Vulkan is the backend of choice here as is used as an interface to communicate with android's gpus. 
OFFLINE inference was the point- how well can a model run on a mobile phone and what size is the limit? 

<h3>DEMO</h3>

<h3>Architecture Overview</h3>

<p>
  <strong>Fig. 3 — Llama-3B running with XNNPACK backend.</strong><br/>
  On-device inference using XNNPACK, showing token generation, throughput, and 
  real-time performance metrics.
</p>

<img src="https://raw.githubusercontent.com/JVarnica/Execu_Chat/main/docs/llama3B-XNNPACK.gif" height="800" alt="Llama 3B XNNPACK runtime demo" />

<br/><br/>

<p>
  <strong>Fig. 4 — Llama-3B running with Vulkan backend.</strong><br/>
  On-device inference using Vulkan GPU acceleration, highlighting improved 
  throughput and GPU utilisation.
</p>

<img src="https://raw.githubusercontent.com/JVarnica/Execu_Chat/main/docs/llama3B-Vulkan.gif" height="800" alt="Llama 3B Vulkan runtime demo" />

### Metrics on Galaxy Z-Fold7

| Model    | Backend | Tokens Generated | Tok/s | TTFT (s) | Prefill Tok/s |
|----------|---------|------------------|-------|----------|---------------|
| Qwen     | XNNPACK | 183              | 16.9  | 0.2      | 116.7         |
| Llama-3B | VULKAN  | 751              | 20.2  | 0.1      | 116.8         |
| Llama-3B | XNNPACK | 751              | 14.5  | 0.1      | 127.0         |
| Llama-1B | VULKAN  | 644              | 59.7  | 0.1      | 137.3         |
| Llama-1B | XNNPACK | 751              | 45.0  | 0.1      | 166.7         |


Prompt sequence for all runs. 
- what is the capital of spain? 
- What can i do there? Be detailed.

### Exporting Models

To use the models for inference you need to create an executorch program,
- first step is to export with torch.export() to get the Intermediate representation so you have the operator list, and model is deterministic.
- Then you delegate/lower the program to your desired backend, optimizing the model for this backend.

This is rather complicated for a LLM which is why the Executorch team have created an export script, export_llm which makes this simple.
For more information read the llama ReadMe https://github.com/pytorch/executorch/tree/main/examples/models/llama

The main model is Llama3.2-3B-QLORA-8da4w from executorch community as already quantized to int4, which will have less quantization errors than qmode,
which is a graph source transformation. It is specified to use P2TE quantization if a specific backend is desired anyway. So you can use your own quantized
checkpoint aswell, which needs to be transformed from the huggingface format to meta format- use script convert_weights.py.
For more information before you export it look at the ReadMe in examples/models/llama,llava,qwen,phi4,etc, all more complicated
models which can be used are shown in the examples. Otherwise you need to do this conversion yourself.

#### Llama export 
python -m executorch.examples.models.llama.export_llama \
--model "llama3_2" \
--checkpoint /home/julien/Documents/Juju/llm_ft/llama3B_QLORA4W/consolidated.00.pth \
--params /home/julien/Documents/Juju/llm_ft/llama3B_QLORA4W/params.json \
-qat \ #whether checkpoint pre-quantized using qat
-lora 16 \ #rank of lora adaptors. 0 is none which is default
--preq_mode 8da4w_output_8da8w \ #quantization mode for pre-quantized checkpoint
--preq_group_size 32 \ #group size pre-quantized checkpoint
--preq_embedding_quantize 8,0 \ #pre embedding quantize '<bitwidth>,<groupsize>'
--use_sdpa_with_kv_cache \ # whether to use spda_with_kv_cache op when kv on
-kv \ # kv_cache
-vulkan or xnnpack\ # backend
-d fp32 \ #dtype
--max_seq_length 1024 \
--max_context_length 2048 \
--output_name "Llama3B-QLORA_8da4wV.pte" \
--metadata '{"get_bos_id":128000, "get_eos_ids":[128009, 128001]}'

As it is from checkpoint need to say how it looks like so, if pre-quantized, if LoRA/QLoRA, group size etc. When checkpoint not pre-quantized don't include these params.
This creates a .pte file called Llama3B-QLORA-8da4wV, with a size of 2.81GB. Which is small enough to run on any phone with 8gb RAM. More details
on the benchmarks between models on galaxy flipz7 (12gb RAM) and galaxy24 (8gb RAM) for vulkan and xnnpack backends. 

Another important thing to note when exporting is you can also put args in a yaml file. This is the code snippet to export qwen3. You use the preset config
for the params so you pick whether 0.6b, 1.7b and 4b config for whichever model you are using.  One thing to note is if you are exporting llama you have
to provide the checkpoint, for others such as qwen you don't need to.

#### Qwen Export
python -m extension.llm.export.export_llm \
--config /home/julien/Documents/Juju/qwen3_4B/qwen3_4b8da4w.yaml \
+base.model_class="qwen3_4b" \
+base.params="/home/julien/Documents/Juju/qwen3_4B/4b_config.json" \
+export.output_name="/home/julien/Documents/Juju/qwen3_4B/qwen3_4b_xxnpack.pte"

qwen3_4b8da4w.yaml
base:
metadata: '{"get_bos_id": 151644, "get_eos_ids":[151645]}'
model:
use_kv_cache: True
use_sdpa_with_kv_cache: True
dtype_override: fp32
quantization:
qmode: 8da4w
export:
max_seq_length: 1024
max_context_length: 2048
backend:
xnnpack:
enabled: True
extended_ops: True

For exporting llava use the examples.models.llava export script don't use optimum-executorch as the created pte will
not have the token embedding method which the LlmModule expects. also only works xnnpack

### Implementation

Each model has its own tokenizer it has been trained with, it seems json or model is fine. Next you need to format the prompt so the model understands
for example for 
- **qwen**: 
<im_start|>user:text<im_end|>
"<|im_start|>assistant\n" 

- **llama**:
"<|start_header_id|>user<|end_header_id|>
text
<|eot_id|>" +
"<|start_header_id|>assistant<|end_header_id|>

This is for user prompt each time you send. Notice assistant is at the end so the model know it needs to 
reply. But at the start you also have to set up the system prompt:
- **qwen** : <|im_start|>system\n$SYSTEM_PLACEHOLDER<\im_end|>
- **llama** : "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n" +
  SYSTEM_PLACEHOLDER +
  "<|eot_id|>"
Without this the model wouldn't understand it is the start of text. 

For vision so llava (CLIP/llama2-7B) you have to prefill the image first, then its USER: TEXT ASSISTANT:
Before you can prefill the image, you first have to preprocess it to 336 dim into an int array which llava expects.

### ASR/Whisper

Executorch has released 




### NOTES/ISSUES

Due to export difficulties only llama has been exported to vulkan the rest remain on xnnpack for time being, the error which occurs when trying to export qwen with vulkan
is: raise SpecViolationError(
torch._export.verifier.SpecViolationError: Mutation node _local_scalar_dense_104 is neither a buffer nor a user input. Buffers to mutate: {'getitem_550': 'layers.34.attention.kv_cache.k_cache', 'getitem_551': 'layers.34.attention.kv_cache.v_cache'}, User inputs to mutate: {}

You get this same SpecViolationError error when exporting llava, meaning the vulkan partitioner is more strict doesn't work with executorch-1.0.1-.1.1.0. 

The same snippet using the pre-quantized llama3.2-3B doesn't work for vulkan for VK_OP_DEQUANTIZE error doesn't have operation in vulkan runtime.
But it also couldn't export normal llama3B to vulkan this was with latest 1.1.0 main to use asr which not available through maven (Jan 2026).
But backwards compatible so exported using 1.0.1 release which i have used for all models just with new runtime.

