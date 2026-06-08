package com.example.httpreading.context.manager;

import com.example.httpreading.context.model.Context;
import com.example.httpreading.context.model.ContextSnapshot;
import com.example.httpreading.context.model.ContextVariable;

import java.util.List;
import java.util.Map;
/**
**职责**: 定义上下文管理器的契约  
**应该包含的方法**:
- `createContext(userId)`: 创建新上下文
- `getContext(contextId)`: 获取上下文
- `updateContext(context)`: 更新上下文
- `mergeContext(contextId1, contextId2, strategy)`: 合并两个上下文
- `snapshotContext(contextId)`: 快照当前上下文(暂不实现了)
- `restoreContext(snapshot)`: 从快照恢复(暂不实现了)
- `cleanupOldContexts(ttl)`: 清理过期上下文
 */
public interface ContextManager {
    public Integer createContext();
    public Integer createContext(String userId, String sessionId);
    public Context getOrCreateContext(String userId, String sessionId);
    public Context getContext(Integer contextId);
    public void updateContext(Context context, Integer contextId);
    public void putVariable(Integer contextId, String key, Object value, String source, Double importance);
    public void appendSnapshot(Integer contextId, String userId, String role, String content, Map<String, Object> metadata);
    public List<ContextSnapshot> getRecentSnapshots(Integer contextId, int limit);
    public Map<String, ContextVariable> getVariables(Integer contextId);
    public String renderHistory(Integer contextId, int limit);
    // public Context mergeContext(Integer contextId1, Integer contextId2, String strategy);
    // public void snapshotContext(Integer contextId);
    // public Context restoreContext(Integer snapshotId);
    public void cleanupOldContexts(Long ttl);
}
