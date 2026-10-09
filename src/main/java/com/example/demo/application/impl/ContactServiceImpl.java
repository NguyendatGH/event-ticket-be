package com.example.demo.application.impl;

import com.example.demo.application.ContactService;
import com.example.demo.application.dto.ContactRequest;
import com.example.demo.application.dto.ContactResponse;
import com.example.demo.domain.contact.ContactMessage;
import com.example.demo.infrastructure.persistence.ContactMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static com.example.demo.application.support.Texts.blankToNull;

@Service
public class ContactServiceImpl implements ContactService {

    private final ContactMessageRepository messages;

    public ContactServiceImpl(ContactMessageRepository messages) {
        this.messages = messages;
    }

    @Override
    @Transactional
    public ContactResponse send(ContactRequest req, UUID userId) {
        ContactMessage m = messages.save(ContactMessage.create(req.name().trim(), req.email().trim(),
                blankToNull(req.subject()), req.message().trim(), userId));
        return new ContactResponse(m.getId(), m.getCreatedAt());
    }
}
