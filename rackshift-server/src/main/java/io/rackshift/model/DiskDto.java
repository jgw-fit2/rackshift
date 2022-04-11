package io.rackshift.model;

import io.rackshift.mybatis.domain.Disk;

public class DiskDto extends Disk {
    private String machineSn;

    public String getMachineSn() {
        return machineSn;
    }

    public void setMachineSn(String machineSn) {
        this.machineSn = machineSn;
    }
}
