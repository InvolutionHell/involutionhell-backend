package com.involutionhell.backend.common.nativeimage;

import com.involutionhell.backend.usercenter.dto.LoginRequest;
import com.involutionhell.backend.usercenter.dto.LoginResponse;
import com.involutionhell.backend.usercenter.dto.UserAuthorizationUpdateRequest;
import com.involutionhell.backend.usercenter.dto.UserView;
import com.involutionhell.backend.usercenter.model.UserAccount;
import java.util.stream.Stream;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class UserCenterRuntimeHints implements RuntimeHintsRegistrar {

    /**
     * 为用户中心相关 DTO 和模型注册原生镜像反射提示
     */
    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        Stream.of(
                LoginRequest.class,
                LoginResponse.class,
                UserAuthorizationUpdateRequest.class,
                UserView.class,
                UserAccount.class
        ).forEach(type -> hints.reflection().registerType(
                type,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS
        ));
    }
}
