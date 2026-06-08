
package com.example.httpreading.domain.user;

import java.time.LocalDateTime;

import jakarta.persistence.*;


@Entity
@Table(name="user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long Id;

    private String username;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "created_at")
    private LocalDateTime createTime;

    public Long getId(){
        return Id;
    }
    
    public void setId(Long ID){
        this.Id = ID;
    }

    public String getUsername(){
        return username;
    }
    
    public void setUsername(String username){
        this.username = username;
    }

    public String getPasswordHash(){
        return passwordHash;
    }
    
    public void setPasswordHash(String passwordHash){
        this.passwordHash = passwordHash;
    }

    public LocalDateTime getCreateTime(){
        return createTime;
    }
    
    public void setCreateTime(LocalDateTime createTime){
        this.createTime = createTime;
    }
}