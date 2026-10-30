package cc.hrva.urlshortener.client;

public sealed interface CloudflareResult<T> {

    record Ok<T>(T value, Integer totalCount) implements CloudflareResult<T> {
    }

    record NotFound<T>() implements CloudflareResult<T> {
    }

    record Refused<T>(int httpStatus, int errorCode, String message) implements CloudflareResult<T> {
    }

    record Unreachable<T>(String failure) implements CloudflareResult<T> {
    }

}
