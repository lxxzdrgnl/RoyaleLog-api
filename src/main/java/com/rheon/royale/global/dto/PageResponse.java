package com.rheon.royale.global.dto;

import lombok.Getter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Getter
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final String sort;

    private PageResponse(Page<T> pageData, String sort) {
        this.content = pageData.getContent();
        this.page = pageData.getNumber();
        this.size = pageData.getSize();
        this.totalElements = pageData.getTotalElements();
        this.totalPages = pageData.getTotalPages();
        this.sort = sort;
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        String sort = page.getSort().stream()
                .map(o -> o.getProperty() + "," + o.getDirection())
                .collect(Collectors.joining(";"));
        return new PageResponse<>(page, sort.isBlank() ? null : sort);
    }

    public static <T, S> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return of(page.map(mapper));
    }
}
