package com.involutionhell.backend.usercenter;


import com.involutionhell.backend.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController("/health_check")
public class HealthTestController {

    @GetMapping("/get")
    public ApiResponse<String> healthCheckViaGet(){
        return ApiResponse.ok("test success");
    }

    @PostMapping("/post")
    public ApiResponse<String> healthCheckViaPost(){
        return ApiResponse.ok("test success");
    }
}
