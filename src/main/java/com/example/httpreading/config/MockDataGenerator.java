package com.example.httpreading.config;

import java.util.Random;

/**
 * 模拟数据生成器
 */
public class MockDataGenerator {

    private static final Random random = new Random();

    private static final String[] JAVA_TOPICS = {
        "Java是一种面向对象的编程语言，由Sun Microsystems于1995年发布。",
        "Java的核心特性包括跨平台性、面向对象、自动垃圾回收和丰富的类库支持。",
        "Java的字节码可以在任何装有JVM的设备上运行，实现了\"一次编写，到处运行\"的理念。",
        "Java SE是标准版，提供了语言核心、GUI、数据库连接等基础功能。",
        "Java EE（现称Jakarta EE）是企业版，专注于分布式计算和Web服务开发。",
        "Java ME是微型版，曾广泛应用于手机和嵌入式设备领域。"
    };

    private static final String[] SPRING_TOPICS = {
        "Spring Framework是Java平台上最流行的企业级应用开发框架。",
        "Spring的核心是IoC容器和AOP（面向切面编程）机制。",
        "依赖注入（DI）是Spring的核心思想，通过控制反转实现松耦合。",
        "Spring Boot简化了Spring应用的配置和部署，约定优于配置。",
        "Spring Cloud提供了一套完整的微服务架构解决方案。",
        "Spring Data简化了数据库访问层，支持JPA、MongoDB、Redis等多种数据源。"
    };

    private static final String[] DATABASE_TOPICS = {
        "关系型数据库采用表格的形式组织数据，通过SQL进行查询和操作。",
        "MySQL是开源关系型数据库的代表，性能稳定，社区活跃。",
        "索引是数据库查询优化的核心技术，B+树是最常用的索引结构。",
        "事务是数据库操作的原子性保障，ACID特性确保数据一致性。",
        "NoSQL数据库打破了传统关系模型，适用于海量数据和高并发场景。",
        "数据库连接池解决了频繁创建连接的性能开销问题。"
    };

    private static final String[] MICROSERVICE_TOPICS = {
        "微服务架构将单体应用拆分为多个独立部署的服务单元。",
        "每个微服务负责特定的业务功能，可以独立开发、测试和部署。",
        "服务治理包括服务注册、发现、负载均衡和熔断等核心功能。",
        "API网关是微服务的统一入口，负责请求路由、认证和限流。",
        "分布式事务是微服务架构中的难题，通常采用最终一致性方案。",
        "服务间通信可以通过HTTP/REST、gRPC或消息队列实现。"
    };

    private static final String[] CONTAINER_TOPICS = {
        "Docker是一个开源的容器化平台，革命性地改变了应用交付方式。",
        "容器相比虚拟机更轻量，启动更快，资源消耗更低。",
        "Dockerfile定义了容器的构建步骤，镜像层叠技术节省存储空间。",
        "Kubernetes是容器编排的事实标准，支持自动化部署和扩缩容。",
        "Pod是Kubernetes的最小调度单元，一个Pod可以包含多个容器。",
        "Service和Ingress提供了稳定的网络访问入口，与Pod的生命周期解耦。"
    };

    private static final String[] PYTHON_TOPICS = {
        "Python是一种解释型高级语言，语法简洁优美，适合快速开发。",
        "Python的动态类型和自动内存管理让开发者专注于业务逻辑。",
        "Python标准库极其丰富，被称为\"batteries included\"语言。",
        "pip是Python的包管理工具，PyPI上有超过30万个第三方包。",
        "Python的应用领域涵盖Web开发、数据科学、人工智能和自动化运维。",
        "GIL限制了Python多线程的并行执行，但进程并行和异步IO可以规避这一限制。"
    };

    private static final String[] ALGORITHM_TOPICS = {
        "算法是解决问题的精确步骤描述，数据结构是组织数据的方式。",
        "时间复杂度和空间复杂度是评估算法效率的两大维度。",
        "二分查找将有序数组的搜索时间从O(n)降低到O(log n)。",
        "哈希表提供常数时间级别的查找、插入和删除操作。",
        "排序算法是算法学习的基础，快速排序、归并排序和堆排序最为常用。",
        "动态规划通过拆分问题、存储子结果避免重复计算，显著提升效率。"
    };

    private static final String[] NETWORK_TOPICS = {
        "TCP/IP协议栈是互联网的基础，OSI七层模型是理论参考框架。",
        "HTTP是无状态的应用层协议，HTTP/2和HTTP/3在性能上做了大幅优化。",
        "TCP提供可靠的面向连接传输，通过三次握手建立连接，四次挥手关闭连接。",
        "DNS将域名解析为IP地址，是互联网的\"电话簿\"服务。",
        "CDN通过就近缓存内容，显著提升用户访问速度和体验。",
        "WebSocket实现了服务端主动推送，解决了HTTP轮询的效率问题。"
    };

    private static final String[][] ALL_TOPICS = {
        JAVA_TOPICS, SPRING_TOPICS, DATABASE_TOPICS, MICROSERVICE_TOPICS,
        CONTAINER_TOPICS, PYTHON_TOPICS, ALGORITHM_TOPICS, NETWORK_TOPICS
    };

    private static final String[] CHAPTER_TITLES = {
        "概述", "环境搭建", "核心概念", "基础语法", "数据类型",
        "控制流", "函数与模块", "面向对象", "异常处理", "文件操作",
        "进阶用法", "设计模式", "性能优化", "最佳实践", "调试技巧",
        "测试方法", "部署上线", "扩展阅读", "实战案例", "原理剖析"
    };

    /**
     * 根据书籍主题和章节索引生成章节内容
     * @param topicIndex 主题索引（0-7）
     * @param chapterIndex 章节索引（0-19）
     * @return 章节内容字符串
     */
    public static String generateChapterContent(int topicIndex, int chapterIndex) {
        String[] topics = ALL_TOPICS[topicIndex % ALL_TOPICS.length];
        String topic = topics[topicIndex % topics.length];
        String title = "第" + (chapterIndex + 1) + "章 " + CHAPTER_TITLES[chapterIndex % CHAPTER_TITLES.length];

        StringBuilder sb = new StringBuilder();
        sb.append(title).append("\n\n");

        // 引言（约80字）
        sb.append(topic).append("\n");

        // 中间段落（3-5段，每段约100字）
        int paragraphCount = 3 + random.nextInt(3);
        for (int i = 0; i < paragraphCount; i++) {
            sb.append("\n");
            sb.append(generateParagraph(topics));
        }

        // 总结（约80字）
        sb.append("\n\n");
        sb.append("本章小结：");
        sb.append(topic.replace("是", "的关键在于").replace("。", "。"));
        sb.append("掌握本章内容后，读者可以");

        String[] skills = {"理解其核心原理", "独立进行开发实践", "应用于实际项目", "进行性能调优"};
        sb.append(skills[random.nextInt(skills.length)]);
        sb.append("。");

        return sb.toString();
    }

    private static String generateParagraph(String[] topics) {
        StringBuilder sb = new StringBuilder();
        // 第一句（40字）
        sb.append(topics[random.nextInt(topics.length)]);
        // 第二句（60字）
        sb.append(topics[(random.nextInt(topics.length))]);
        // 第三句（50字）
        String third = topics[random.nextInt(topics.length)];
        sb.append(third.substring(0, third.indexOf("。") + 1));
        return sb.toString();
    }

    /**
     * 生成书籍简介
     */
    public static String generateBookIntro(String title, String author) {
        return "本书由" + author + "编写，是学习" + title.replaceAll("^[^\\u4e00-\\u9fa5]+", "") + "的经典教材。"
            + "内容详实丰富，配有大量实例代码，适合初学者入门和进阶学习。"
            + "全书贯穿理论与实践，帮助读者建立完整的知识体系。";
    }
}
