USE bookstore;
INSERT INTO book (title, author, cover_url, intro, status, created_at, updated_at) VALUES
('Java核心技术', 'Cay S. Horstmann', 'https://example.com/java.jpg', 'Java经典书籍', '连载中', NOW(), NOW()),
('Spring Boot实战', '丁雪峰', 'https://example.com/spring.jpg', 'Spring Boot必读', '已完结', NOW(), DATE_SUB(NOW(), INTERVAL 1 DAY)),
('Python编程', 'Mark Lutz', 'https://example.com/python.jpg', 'Python入门经典', '连载中', NOW(), DATE_SUB(NOW(), INTERVAL 2 DAY)),
('算法导论', 'Thomas H. Cormen', 'https://example.com/algo.jpg', '算法经典教材', '已完结', NOW(), DATE_SUB(NOW(), INTERVAL 3 DAY)),
('深入理解计算机系统', 'Randal E. Bryant', 'https://example.com/csapp.jpg', 'CSAPP经典', '连载中', NOW(), DATE_SUB(NOW(), INTERVAL 5 DAY)),
('Redis设计与实现', '黄健宏', 'https://example.com/redis.jpg', 'Redis源码分析', '已完结', NOW(), DATE_SUB(NOW(), INTERVAL 7 DAY)),
('MySQL必知必会', 'Ben Forta', 'https://example.com/mysql.jpg', 'MySQL入门', '连载中', NOW(), DATE_SUB(NOW(), INTERVAL 10 DAY)),
('Docker容器实战', 'James Turnbull', 'https://example.com/docker.jpg', 'Docker入门到实践', '已完结', NOW(), NOW()),
('Kubernetes权威指南', '龚正', 'https://example.com/k8s.jpg', 'K8S实战', '连载中', NOW(), DATE_SUB(NOW(), INTERVAL 4 DAY)),
('微服务设计', 'Sam Newman', 'https://example.com/microservice.jpg', '微服务架构指南', '已完结', NOW(), DATE_SUB(NOW(), INTERVAL 6 DAY));