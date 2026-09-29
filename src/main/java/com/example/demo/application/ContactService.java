package com.example.demo.application;

import com.example.demo.application.dto.ContactRequest;
import com.example.demo.application.dto.ContactResponse;

import java.util.UUID;

public interface ContactService {

    ContactResponse send(ContactRequest req, UUID userId);
}
