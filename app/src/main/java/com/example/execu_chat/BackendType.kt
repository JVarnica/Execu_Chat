package com.example.execu_chat
// what backend you pick. depreciated as vulkan aar includes both but keep.

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