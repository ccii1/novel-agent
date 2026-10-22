package dev.novel.repository.mapper;

import org.apache.ibatis.annotations.*;
import java.util.Map;

@Mapper
public interface UserMapper {
    @Select("SELECT username,password_hash FROM app_users WHERE username=#{username}")
    Map<String,Object> find(@Param("username") String username);
    @Insert("INSERT INTO app_users(username,password_hash) VALUES(#{username},#{password})")
    int create(@Param("username") String username,@Param("password") String password);
}
