package com.kooo.evcam.remote.core;

/**
 * 聊天标识接口
 * 统一封装不同平台的聊天 ID
 */
public interface ChatIdentifier {
    
    /**
     * 获取字符串形式的 ID（用于日志和存储）
     */
    String getId();
    
    /**
     * 获取所属平台
     */
    RemotePlatform getPlatform();
    
    // ==================== 静态工厂方法 ====================
    
    /**
     * 创建钉钉聊天标识
     */
    static DingTalkChatId dingtalk(String conversationId, String conversationType, String userId) {
        return new DingTalkChatId(conversationId, conversationType, userId);
    }
    
    /**
     * 创建 Telegram 聊天标识
     */
    static TelegramChatId telegram(long chatId) {
        return new TelegramChatId(chatId);
    }
    
    /**
     * 创建飞书聊天标识
     */
    static FeishuChatId feishu(String chatId) {
        return new FeishuChatId(chatId);
    }
    
    // ==================== 具体实现类 ====================
    
    /**
     * 钉钉聊天标识
     */
    class DingTalkChatId implements ChatIdentifier {
        private final String conversationId;
        private final String conversationType;  // "1"=单聊, "2"=群聊
        private final String userId;
        
        public DingTalkChatId(String conversationId, String conversationType, String userId) {
            this.conversationId = conversationId;
            this.conversationType = conversationType;
            this.userId = userId;
        }
        
        @Override
        public String getId() {
            return conversationId;
        }
        
        @Override
        public RemotePlatform getPlatform() {
            return RemotePlatform.DINGTALK;
        }
        
        public String getConversationId() {
            return conversationId;
        }
        
        public String getConversationType() {
            return conversationType;
        }
        
        public String getUserId() {
            return userId;
        }
        
        @Override
        public String toString() {
            return "DingTalk[" + conversationId + "]";
        }
    }
    
    /**
     * Telegram 聊天标识
     */
    class TelegramChatId implements ChatIdentifier {
        private final long chatId;
        
        public TelegramChatId(long chatId) {
            this.chatId = chatId;
        }
        
        @Override
        public String getId() {
            return String.valueOf(chatId);
        }
        
        @Override
        public RemotePlatform getPlatform() {
            return RemotePlatform.TELEGRAM;
        }
        
        public long getChatId() {
            return chatId;
        }
        
        @Override
        public String toString() {
            return "Telegram[" + chatId + "]";
        }
    }
    
    /**
     * 飞书聊天标识
     */
    class FeishuChatId implements ChatIdentifier {
        private final String chatId;
        
        public FeishuChatId(String chatId) {
            this.chatId = chatId;
        }
        
        @Override
        public String getId() {
            return chatId;
        }
        
        @Override
        public RemotePlatform getPlatform() {
            return RemotePlatform.FEISHU;
        }
        
        public String getChatId() {
            return chatId;
        }
        
        @Override
        public String toString() {
            return "Feishu[" + chatId + "]";
        }
    }
}
