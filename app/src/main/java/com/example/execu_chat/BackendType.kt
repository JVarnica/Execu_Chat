package com.example.execu_chat

enum class BackendType {
    XNNPACK,
    VULKAN;

    companion object {
        fun getDisplayName(type: BackendType): String {
            return  when (type) {
                XNNPACK -> "XNNPACK"
                VULKAN -> "VULKAN"
            }
        }
    }
}