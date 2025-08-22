package likelion.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
@Getter  @Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "post_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "post_id")
    )
    private Post post; // FK: comment.post_id

    @Column(name = "user_name", nullable = false, updatable = false)
    private String userName;

    @Column(name = "content", nullable = false, length = 500)
    private String content;

    // 생성 시각
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime created_at;

    // 생성 시 기본값 세팅
    @PrePersist
    void onCreate() {
        // 무조건 "익명"으로 고정
        this.userName = "익명";
        this.created_at = LocalDateTime.now();
    }
}


