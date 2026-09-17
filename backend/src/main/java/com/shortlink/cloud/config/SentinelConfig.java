package com.shortlink.cloud.config;

import com.alibaba.csp.sentinel.annotation.aspectj.SentinelResourceAspect;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sentinel 流量防护配置。
 *
 * <p>与 Redis 限流的分工：
 * <ul>
 *   <li><b>Redis + Lua</b>：按 IP 维度的业务限流（防刷），可跨实例共享计数</li>
 *   <li><b>Sentinel</b>：接口级总量兜底，保护后端不被突发流量打垮，
 *       触发后走本地快速失败，不需要 Redis 往返</li>
 * </ul>
 *
 * @author shortlink-cloud
 */
@Slf4j
@Configuration
public class SentinelConfig {

    /** 创建接口的资源名，需与 {@code @SentinelResource} 一致。 */
    public static final String RESOURCE_LINK_CREATE = "link:create";
    /** 跳转接口的资源名（保留给后续接入 dashboard 时使用）。 */
    public static final String RESOURCE_LINK_REDIRECT = "link:redirect";

    /** 创建接口 QPS 上限。创建是写操作，阈值远低于跳转。 */
    private static final double CREATE_QPS_LIMIT = 500D;
    /** 跳转接口 QPS 上限（单机阈值）。 */
    private static final double REDIRECT_QPS_LIMIT = 5000D;

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    /**
     * 以代码方式声明流控规则。
     *
     * <p>不依赖 Sentinel Dashboard：本地规则即可保证「无外部依赖也能防护」，
     * 后续接入 dashboard 时这些规则会被远端配置覆盖。
     */
    @PostConstruct
    public void initFlowRules() {
        List<FlowRule> rules = new ArrayList<>(2);
        rules.add(flowRule(RESOURCE_LINK_CREATE, CREATE_QPS_LIMIT));
        rules.add(flowRule(RESOURCE_LINK_REDIRECT, REDIRECT_QPS_LIMIT));
        FlowRuleManager.loadRules(rules);
        log.info("Sentinel 流控规则已加载: {} qps={}, {} qps={}",
                RESOURCE_LINK_CREATE, CREATE_QPS_LIMIT, RESOURCE_LINK_REDIRECT, REDIRECT_QPS_LIMIT);
    }

    private FlowRule flowRule(String resource, double count) {
        FlowRule rule = new FlowRule();
        rule.setResource(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(count);
        // 快速失败：超出阈值立即拒绝，不排队，避免请求堆积把线程池拖死
        rule.setControlBehavior(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        return rule;
    }
}
