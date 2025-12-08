package com.zwbd.dbcrawlerv4.ai.repository;

import com.zwbd.dbcrawlerv4.ai.entity.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * @Author: wnli
 * @Date: 2025/11/24 16:32
 * @Desc:
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {
}
