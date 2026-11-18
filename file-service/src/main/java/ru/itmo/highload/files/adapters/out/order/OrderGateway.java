package ru.itmo.highload.files.adapters.out.order;
import java.util.*;
import feign.FeignException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import ru.itmo.highload.common.dto.out.ApiError;
import ru.itmo.highload.common.error.ApiException;
import ru.itmo.highload.files.application.OrderSnapshot;
import ru.itmo.highload.files.application.ports.OrderAccess;
public final class OrderGateway implements OrderAccess {
    private final OrderClient client;
    private final ObjectMapper mapper;
    public OrderGateway(OrderClient client,ObjectMapper mapper) { this.client=client;this.mapper=mapper; }
    public OrderSnapshot require(UUID id,String bearer) {
        try {
            String trace=MDC.get("traceId");
            var result=client.access(id,bearer,trace==null?UUID.randomUUID().toString():trace);
            if(result==null || !id.equals(result.id()) || result.organizationId()==null || result.version()<0
                    || !Set.of("DRAFT","SUBMITTED","CONFIRMED","REJECTED","CANCELLED","IN_COOKING","READY","COMPLETED").contains(result.status()))
                throw new IllegalStateException("Invalid Order access response");
            return result;
        } catch(FeignException error) {
            if(Set.of(401,403,404).contains(error.status())) {
                try {
                    var body=mapper.readValue(error.contentUTF8(),ApiError.class);
                    String expected=switch(error.status()) { case 401->"AUTHENTICATION_REQUIRED";case 403->"ACCESS_DENIED";default->"RESOURCE_NOT_FOUND"; };
                    if(body!=null && expected.equals(body.code()) && body.message()!=null && body.fieldErrors()!=null)
                        throw new ApiException(HttpStatus.valueOf(error.status()),body.code(),body.message(),body.fieldErrors());
                } catch(java.io.IOException invalid) { }
            }
            throw unavailable();
        } catch(ApiException known) { throw known; }
        catch(RuntimeException invalid) { throw unavailable(); }
    }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"DEPENDENCY_UNAVAILABLE","Сервис заказов временно недоступен"); }
}
