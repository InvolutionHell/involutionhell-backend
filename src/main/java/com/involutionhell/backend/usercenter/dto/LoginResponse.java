package com.involutionhell.backend.usercenter.dto;

public record LoginResponse(String tokenName, String tokenValue, UserView user) {
}
