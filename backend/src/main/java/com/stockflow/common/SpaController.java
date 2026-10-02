package com.stockflow.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {

    @GetMapping(value = {
            "/",
            "/{x:[\\w\\-]+}",
            "/{x:^(?!api$|actuator$|v3$|swagger-ui$)[\\w\\-]+}/**"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
