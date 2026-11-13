# 学习工程

每个独立工程位于 `projects/<project-id>/`，拥有自己的实现、配置、测试与说明。根据学习目标扩展已有工程，或在需要独立边界时建立新工程。

当前登记的基础服务为 `foundation-service/`，负责提供基础信息和健康接口，并消费 Java Web 公共能力。运行与验证步骤见仓库[开发指南](../docs/DEVELOPMENT.md)。

首个业务学习工程为 [order-lab](order-lab/README.md)，独立管理前后端代码、MySQL 表、Redis 键与 RabbitMQ 资源，覆盖下单至积分的可靠性链路。后端注释详细，前端不新增注释；运行与生产适用性待验收。

新工程遵循[接入指南](../docs/PROJECT_ONBOARDING.md)。允许 Java、Python 和组合技术栈；按实际需要建立前端、数据库或其他资源。当前进度统一记录在 [PROGRESS.md](../docs/PROGRESS.md)。
