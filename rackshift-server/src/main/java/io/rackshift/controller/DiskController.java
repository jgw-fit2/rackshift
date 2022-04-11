package io.rackshift.controller;

import io.rackshift.model.ResultHolder;
import io.rackshift.service.DiskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("disk")
public class DiskController {
    @Resource
    private DiskService diskService;

    @GetMapping("/all")
    public ResultHolder all() {
        return ResultHolder.success(diskService.getAllDisks());
    }
}
