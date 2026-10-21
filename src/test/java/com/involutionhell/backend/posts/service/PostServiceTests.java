package com.involutionhell.backend.posts.service;

import com.involutionhell.backend.common.error.AccessDeniedBusinessException;
import com.involutionhell.backend.posts.dto.PostRequest;
import com.involutionhell.backend.posts.model.Post;
import com.involutionhell.backend.posts.model.PostStatus;
import com.involutionhell.backend.posts.model.PostVisibility;
import com.involutionhell.backend.posts.repository.PostRepository;
import com.involutionhell.backend.usercenter.repository.UserAccountRepository;
import com.involutionhell.backend.usercenter.service.UserCenterService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** PostService 的 slug 生成与 owner 校验。仓储全部 mock，不碰数据库。 */
class PostServiceTests {

    private static final long AUTHOR = 7L;

    private final PostRepository repo = mock(PostRepository.class);
    private final PostService service = new PostService(repo,
            mock(UserCenterService.class), mock(UserAccountRepository.class), mock(ObjectMapper.class));

    @Test
    void create_slugFromTitle_stripsAccentsAndPunctuation() {
        assertThat(createdSlug("Héllo, Wörld!", null)).isEqualTo("hello-world");
    }

    @Test
    void create_nonAsciiTitle_fallsBackToPost() {
        assertThat(createdSlug("我的第一篇文章", null)).isEqualTo("post");
    }

    @Test
    void create_requestedSlugIsSanitized() {
        assertThat(createdSlug("t", "  My Slug!! ")).isEqualTo("my-slug");
    }

    @Test
    void create_slugCollision_appendsFirstFreeSuffix() {
        when(repo.countByAuthorAndSlugPrefix(AUTHOR, "foo")).thenReturn(2);
        when(repo.findByAuthorAndSlug(AUTHOR, "foo-3")).thenReturn(Optional.of(post(99L, AUTHOR, "foo-3")));
        when(repo.findByAuthorAndSlug(AUTHOR, "foo-4")).thenReturn(Optional.empty());

        assertThat(createdSlug("Foo", null)).isEqualTo("foo-4");
    }

    @Test
    void update_byNonOwner_denied() {
        when(repo.findById(1L)).thenReturn(Optional.of(post(1L, AUTHOR, "foo")));

        assertThatThrownBy(() -> service.update(8L, 1L, request("t", null)))
                .isInstanceOf(AccessDeniedBusinessException.class);
        verify(repo, never()).update(anyLong(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void update_toSlugAlreadyTaken_rejected() {
        when(repo.findById(1L)).thenReturn(Optional.of(post(1L, AUTHOR, "foo")));
        when(repo.findByAuthorAndSlug(AUTHOR, "bar")).thenReturn(Optional.of(post(2L, AUTHOR, "bar")));

        assertThatThrownBy(() -> service.update(AUTHOR, 1L, request("t", "Bar")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void delete_byNonOwner_denied() {
        when(repo.findById(1L)).thenReturn(Optional.of(post(1L, AUTHOR, "foo")));

        assertThatThrownBy(() -> service.delete(8L, 1L))
                .isInstanceOf(AccessDeniedBusinessException.class);
        verify(repo, never()).delete(any());
    }

    private String createdSlug(String title, String slug) {
        when(repo.insert(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(AUTHOR, request(title, slug));
        ArgumentCaptor<Post> saved = ArgumentCaptor.forClass(Post.class);
        verify(repo).insert(saved.capture());
        return saved.getValue().slug();
    }

    private static PostRequest request(String title, String slug) {
        return new PostRequest(title, null, List.of(), "body", null, slug);
    }

    private static Post post(Long id, Long authorId, String slug) {
        return new Post(id, authorId, slug, "t", null, List.of(), "body", null,
                PostVisibility.PUBLIC, PostStatus.PUBLISHED, null, null, 0, null, null);
    }
}
