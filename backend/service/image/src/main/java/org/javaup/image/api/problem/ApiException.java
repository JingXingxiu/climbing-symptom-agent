package org.javaup.image.api.problem;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * A deliberate image-service API failure rendered as RFC 9457 problem details.
 */
public final class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;

    public ApiException(HttpStatusCode status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public static ApiException notImplemented(String code, String detail) {
        return new ApiException(HttpStatus.NOT_IMPLEMENTED, code, detail);
    }

    public HttpStatusCode status() {
        return status;
    }

    public String code() {
        return code;
    }
}
