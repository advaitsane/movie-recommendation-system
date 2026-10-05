package com.movies.user.service;

import com.movies.user.dto.LoginRequest;
import com.movies.user.dto.RecordActivityRequest;
import com.movies.user.dto.RegisterRequest;
import com.movies.user.model.User;

public interface IUserService {

    User register(RegisterRequest request);

    LoginResult login(LoginRequest request);

    User getUserById(Long id);

    void recordActivity(Long userId, RecordActivityRequest request);
}
