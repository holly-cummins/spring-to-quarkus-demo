package me.escoffier.quarkus.todo;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/")
public class FrontendController {

    @GetMapping
    public ResponseEntity redirect() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create("todo.html"));
        return new ResponseEntity<>(headers, HttpStatus.TEMPORARY_REDIRECT);
    }
}
