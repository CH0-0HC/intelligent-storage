package com.ai.smartinventory.dto;

import lombok.Data;

@Data
public class PageQueryDTO {
    
    private Integer page = 1;
    
    private Integer size = 10;
    
    private String keyword;
    
    private Long categoryId;
    
    private Integer status;
    
    private String sort = "createTime";
    
    private String order = "desc";
}