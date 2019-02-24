package me.sumanvanan.entity;

import lombok.Data;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_session")
@Data
public class Session {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private LocalDateTime start;
    private LocalDateTime end;
    @ManyToOne
    @JoinColumn(name = "user_id")
    private TelegramUser user;
    @Column(name = "is_cheat_day")
    private boolean isCheatDay = false; // set default value to false
    @Column(name = "activities_completed")
    private String activitiesCompleted;
}
