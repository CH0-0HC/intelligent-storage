package com.ai.smartinventory.common;

import lombok.Data;
import java.io.Serializable;

@Data
public class Result<T> implements Serializable {
    
    private Integer code;
    private String message;
    private T data;
    
    public static <T> Result<T> success() {
        return success(null);
    }
    
    public static <T> Result<T> success(T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }
    
    public static <T> Result<T> success(String message, T data) {
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage(message);
        result.setData(data);
        return result;
    }
    
    /**
     * 构造业务错误结果（code = 400，语义为「请求被业务规则拒绝」）。
     *
     * 【测试报告#11】原先固定 500：{@code {"code":500,"message":"账号已被禁用"}} 这类
     * **业务拒绝**被标成了服务端内部错误，语义不对，也让日志/监控分不清
     * 「用户被拒」和「系统出错」。现改为 400，与本类既有的 {@code error(400, …)}
     * 及 GlobalExceptionHandler 的参数校验出口同一档。
     *
     * <p><b>例外：</b>服务端内部失败（写库返回 false、聚合查询抛异常等）不适用 400，
     * 必须显式写 {@code error(500, "…")} —— 全库这类调用点已逐个标注。
     * 需要精确语义时仍优先用 {@link #error(Integer, String)}。
     *
     * @deprecated 默认码已从 500 改为 400；新代码请显式传错误码。
     */
    @Deprecated
    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.setCode(400);
        result.setMessage(message);
        return result;
    }
    
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
    
    public static <T> Result<T> unauthorized(String message) {
        return error(401, message);
    }
    
    public static <T> Result<T> forbidden(String message) {
        return error(403, message);
    }
}
