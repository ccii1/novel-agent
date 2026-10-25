package dev.novel.integration.model;

final class RetryableModelRequest extends IllegalStateException {
    RetryableModelRequest(int status) { super("模型服务暂时不可用，HTTP "+status); }
}
