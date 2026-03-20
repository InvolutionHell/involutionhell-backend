package com.involutionhell.backend.usercenter.dto;

import java.util.Set;

public record UserAuthorizationUpdateRequest(Set<String> roles, Set<String> permissions) {
}
