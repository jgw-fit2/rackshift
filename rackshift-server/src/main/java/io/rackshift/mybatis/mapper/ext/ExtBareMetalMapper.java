package io.rackshift.mybatis.mapper.ext;

import io.rackshift.mybatis.domain.BareMetal;


public interface ExtBareMetalMapper {


    BareMetal selectByMac(String mac);

}