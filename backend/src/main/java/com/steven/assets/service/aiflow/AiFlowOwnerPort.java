package com.steven.assets.service.aiflow;

/** 使用既有 configured-admin 權威，不在觀測功能複製帳號選擇規則。 */
public interface AiFlowOwnerPort {
    long configuredOwner();
    long readOwner();
}
