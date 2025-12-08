package com.zwbd.dbcrawlerv4.document.entity;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * @Author: wnli
 * @Date: 2025/12/8 16:55
 * @Desc:
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Context {

    private String id;
    @JsonAlias("text")
    private String text;
    private Map<String, Object> metadata = new HashMap<>();

    public Context(String id, String text) {
        this.id = id;
        this.text = text;
    }
}
