package likelion.domain.entity;

import jakarta.persistence.*;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "comments")
@NoArgsConstructor
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "password", length = 4)
    private String password;

    @Column(name = "content")
    private String content;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(name = "created_at")
    private LocalDate created_at;

    @Column(name = "updated_at")
    private LocalDate updated_at;

    @ManyToOne
    @JoinColumn(
            name = "postId",
            nullable = false,
            foreignKey = @ForeignKey(name = "post_id")
    )
    private Post post; // FK: comment.post_id

    @Column(name = "user_check") //게시글 작성자인지 여부
    private boolean user_check;

//나중에
//    public void userCheck(String commentUserName, String commentPassword){
//        if(commentUserName.equals(post.getUserName()) && commentPassword.equals(post.getPassword())){
//            user_check = true;
//        }else{
//            user_check = false;
//        }
//    }
}
