### Online Inference-vLLM

The models on mobile phone are okay but too small to have a proper conversation with, therefore to use
smarter models you either host one yourself or call an API (OpenAI/Claude). Self hosting was the option
I went for as I am cheap. 
But next was do i use vllm or tensor-rt. I decided to use vLLM for one reason it uses OpenAI API, so I
just need to call the OpenAI API (v1/chat/completions) as a HTTP request. This makes it easy to add other
users into the system, just need to sort out the hyperparameters.
For example on 5060ti i can run qwen at 32k context len at 0.9 gpu usage, but if I were to add a 
user the model would need to be loaded with 16k context len. Re-uses weights but pre-allocates cache.

