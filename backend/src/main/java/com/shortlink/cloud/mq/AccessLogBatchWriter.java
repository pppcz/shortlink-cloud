package com.shortlink.cloud.mq;

import com.shortlink.cloud.entity.LinkAccessLog;
import com.shortlink.cloud.mapper.LinkAccessLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 访问明细日志批量写入器。
 *
 * <p>为什么单独抽一层：{@code SqlSessionFactory} / BATCH 执行器 属于基础设施细节，
 * 混在 {@code StatsServiceImpl} 里会让业务逻辑（聚合、UV 去重）无法被纯单元测试覆盖。
 * 抽出来后，统计服务只依赖这个接口，测试直接 mock 掉即可。
 *
 * <p>为什么用 BATCH 执行器而不是「一条 SQL 插多行」：几千个 VALUES 拼出的 SQL 过长，
 * 受 MySQL {@code max_allowed_packet} 限制；BATCH 由驱动侧攒批，更稳且代码更简单。
 *
 * @author shortlink-cloud
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogBatchWriter {

    private final SqlSessionFactory sqlSessionFactory;

    /**
     * 批量写入访问明细。
     *
     * @param logs 日志列表，空列表直接返回
     * @return 写入条数
     */
    public int writeBatch(List<LinkAccessLog> logs) {
        if (logs == null || logs.isEmpty()) {
            return 0;
        }
        // 注意：这里不用 @Transactional —— BATCH 执行器的提交时机由本方法控制，
        // 让外层事务接管反而会出现「以为提交了其实还在攒批」的问题。
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH, false)) {
            LinkAccessLogMapper mapper = session.getMapper(LinkAccessLogMapper.class);
            for (LinkAccessLog log : logs) {
                mapper.insert(log);
            }
            session.commit();
            log.debug("访问明细批量写入 {} 条", logs.size());
            return logs.size();
        }
    }
}
