
-- =========================================================
-- 学生个人综测材料整理系统
-- MySQL 8.0+
-- 字符集：utf8mb4
-- =========================================================

CREATE DATABASE IF NOT EXISTS assessment_system
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE assessment_system;

-- 1. 学院
CREATE TABLE college (
                         id BIGINT PRIMARY KEY AUTO_INCREMENT,
                         code VARCHAR(32) NOT NULL,
                         name VARCHAR(100) NOT NULL,
                         status TINYINT NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
                         created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                             ON UPDATE CURRENT_TIMESTAMP,
                         UNIQUE KEY uk_college_code (code)
) ENGINE=InnoDB COMMENT='学院信息';

-- 2. 规则版本
CREATE TABLE rule_version (
                              id BIGINT PRIMARY KEY AUTO_INCREMENT,
                              college_id BIGINT NOT NULL,
                              academic_year VARCHAR(16) NOT NULL COMMENT '例如2026-2027',
                              version VARCHAR(32) NOT NULL COMMENT '例如v1.0',
                              rule_name VARCHAR(200) NOT NULL,
                              status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                                  COMMENT 'DRAFT/REVIEWING/PUBLISHED/RETIRED',
                              #effective_from DATE DEFAULT NULL,
                              #effective_to DATE DEFAULT NULL,
                              published_at DATETIME DEFAULT NULL,
                              description VARCHAR(500) DEFAULT NULL,
                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                  ON UPDATE CURRENT_TIMESTAMP,
                              CONSTRAINT fk_rule_college
                                  FOREIGN KEY (college_id) REFERENCES college(id),
                              UNIQUE KEY uk_college_year_version
                                  (college_id, academic_year, version),
                              KEY idx_rule_status (college_id, academic_year, status)
) ENGINE=InnoDB COMMENT='学院综测规则版本';

-- 3. 规则来源文件与条款
CREATE TABLE rule_source (
                             id BIGINT PRIMARY KEY AUTO_INCREMENT,
                             rule_version_id BIGINT NOT NULL,
                             source_type VARCHAR(40) NOT NULL
                                 COMMENT 'SCHOOL_POLICY/COLLEGE_POLICY/COMPETITION_LIST/JOURNAL_LIST/NOTICE',
                             file_name VARCHAR(255) NOT NULL,
                             file_hash CHAR(64) DEFAULT NULL COMMENT '原始文件SHA-256',
                             storage_path VARCHAR(500) DEFAULT NULL,
                             page_no INT DEFAULT NULL,
                             clause_no VARCHAR(100) DEFAULT NULL,
                             excerpt TEXT DEFAULT NULL COMMENT '规则依据摘录',
                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             CONSTRAINT fk_source_version
                                 FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                             KEY idx_source_version (rule_version_id)
) ENGINE=InnoDB COMMENT='规则依据及来源';

-- 4. 学校公共竞赛目录
CREATE TABLE competition (
                             id BIGINT PRIMARY KEY AUTO_INCREMENT,
                             source_year VARCHAR(16) NOT NULL COMMENT '目录年份',
                             source_serial_no INT DEFAULT NULL COMMENT '原目录序号',
                             name VARCHAR(255) NOT NULL,
                             category VARCHAR(50) DEFAULT NULL COMMENT '学校目录类别，如A/B/C',
                             attribute VARCHAR(100) DEFAULT NULL COMMENT '竞赛属性',
                             organizer VARCHAR(255) DEFAULT NULL COMMENT '主办单位',
                             campus_organizer VARCHAR(255) DEFAULT NULL COMMENT '校内承办单位',
                             source_id BIGINT DEFAULT NULL COMMENT '来源文件ID',
                             status TINYINT NOT NULL DEFAULT 1,
                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             CONSTRAINT fk_competition_source
                                 FOREIGN KEY (source_id) REFERENCES rule_source(id),
                             KEY idx_competition_name (name),
                             KEY idx_competition_year (source_year, category)
) ENGINE=InnoDB COMMENT='学校竞赛项目目录';

-- 5. 竞赛名称别名
CREATE TABLE competition_alias (
                                   id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                   competition_id BIGINT NOT NULL,
                                   alias VARCHAR(255) NOT NULL,
                                   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   CONSTRAINT fk_alias_competition
                                       FOREIGN KEY (competition_id) REFERENCES competition(id),
                                   UNIQUE KEY uk_competition_alias (competition_id, alias),
                                   KEY idx_alias_name (alias)
) ENGINE=InnoDB COMMENT='竞赛名称别名';

-- 6. 五类测评计算规则
CREATE TABLE score_rule (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            rule_version_id BIGINT NOT NULL,
                            category VARCHAR(20) NOT NULL
                                COMMENT 'MORAL/INTELLECTUAL/PHYSICAL/AESTHETIC/LABOR',
                            item_code VARCHAR(64) NOT NULL
                                COMMENT '例如BASE/PEER_SCORE/BONUS/DEDUCTION',
                            item_name VARCHAR(100) NOT NULL,
                            formula_type VARCHAR(50) NOT NULL
                                COMMENT 'WEIGHTED_SUM/LINEAR_CONVERSION/SUM/DEDUCTION_FLOOR等',
                            weight DECIMAL(8,4) DEFAULT NULL COMMENT '分项权重',
                            min_score DECIMAL(10,4) DEFAULT NULL,
                            max_score DECIMAL(10,4) DEFAULT NULL,
                            parameters JSON DEFAULT NULL COMMENT '公式参数及适用条件',
                            sort_no INT NOT NULL DEFAULT 0,
                            source_id BIGINT DEFAULT NULL,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            CONSTRAINT fk_score_rule_version
                                FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                            CONSTRAINT fk_score_rule_source
                                FOREIGN KEY (source_id) REFERENCES rule_source(id),
                            UNIQUE KEY uk_score_rule_item
                                (rule_version_id, category, item_code),
                            KEY idx_score_rule_category (rule_version_id, category)
) ENGINE=InnoDB COMMENT='五类测评计算规则';

-- 7. 加分、扣分项目规则
CREATE TABLE bonus_rule (
                            id BIGINT PRIMARY KEY AUTO_INCREMENT,
                            rule_version_id BIGINT NOT NULL,
                            competition_id BIGINT DEFAULT NULL,
                            rule_code VARCHAR(100) NOT NULL,
                            category VARCHAR(20) NOT NULL
                                COMMENT 'MORAL/INTELLECTUAL/PHYSICAL/AESTHETIC/LABOR',
                            item_name VARCHAR(255) NOT NULL COMMENT '具体加扣分项目',
                            item_type VARCHAR(20) NOT NULL
                                COMMENT 'BONUS/DEDUCTION',
                            activity_level VARCHAR(50) DEFAULT NULL
                                COMMENT 'NATIONAL/PROVINCIAL/CITY/SCHOOL/COLLEGE等',
                            award_level VARCHAR(50) DEFAULT NULL
                                COMMENT 'FIRST/SECOND/THIRD/EXCELLENT/PARTICIPATION等',
                            role_type VARCHAR(50) DEFAULT NULL
                                COMMENT 'LEADER/MEMBER/FIRST_AUTHOR/SECOND_AUTHOR等',
                            score DECIMAL(10,4) NOT NULL DEFAULT 0,
                            max_score DECIMAL(10,4) DEFAULT NULL COMMENT '单项或累计上限',
                            unit VARCHAR(40) DEFAULT NULL COMMENT '次/小时/篇/项等',
                            conditions JSON DEFAULT NULL COMMENT '其他资格及限制条件',
                            conflict_group VARCHAR(100) DEFAULT NULL
                                COMMENT '同项目取最高、不可重复计分的分组标识',
                            conflict_strategy VARCHAR(40) DEFAULT NULL
                                COMMENT 'MAX_ONLY/NO_ACCUMULATE/ACCUMULATE等',
                            sort_no INT NOT NULL DEFAULT 0,
                            source_id BIGINT DEFAULT NULL,
                            status TINYINT NOT NULL DEFAULT 1,
                            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                ON UPDATE CURRENT_TIMESTAMP,
                            CONSTRAINT fk_bonus_version
                                FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                            CONSTRAINT fk_bonus_competition
                                FOREIGN KEY (competition_id) REFERENCES competition(id),
                            CONSTRAINT fk_bonus_source
                                FOREIGN KEY (source_id) REFERENCES rule_source(id),
                            UNIQUE KEY uk_bonus_rule_code (rule_version_id, rule_code),
                            KEY idx_bonus_category (rule_version_id, category, item_type),
                            KEY idx_bonus_competition (competition_id)
) ENGINE=InnoDB COMMENT='学院加分扣分规则';

-- 8. 材料要求
CREATE TABLE material_requirement (
                                      id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                      rule_version_id BIGINT NOT NULL,
                                      category VARCHAR(20) NOT NULL,
                                      material_type VARCHAR(100) NOT NULL,
                                      requirement_name VARCHAR(200) NOT NULL,
                                      file_format VARCHAR(100) DEFAULT NULL,
                                      is_required TINYINT NOT NULL DEFAULT 0,
                                      description TEXT DEFAULT NULL,
                                      naming_pattern VARCHAR(255) DEFAULT NULL,
                                      folder_name VARCHAR(100) DEFAULT NULL,
                                      sort_no INT NOT NULL DEFAULT 0,
                                      source_id BIGINT DEFAULT NULL,
                                      created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                      CONSTRAINT fk_material_req_version
                                          FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                                      CONSTRAINT fk_material_req_source
                                          FOREIGN KEY (source_id) REFERENCES rule_source(id),
                                      KEY idx_material_req_category (rule_version_id, category)
) ENGINE=InnoDB COMMENT='学院材料及归档要求';

-- 9. Excel模板及字段映射
CREATE TABLE excel_template (
                                id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                rule_version_id BIGINT NOT NULL,
                                template_type VARCHAR(40) NOT NULL
                                    COMMENT 'ASSESSMENT_FORM/BONUS_DETAIL',
                                template_name VARCHAR(255) NOT NULL,
                                file_location VARCHAR(500) NOT NULL,
                                file_hash CHAR(64) DEFAULT NULL,
                                mapping_config JSON DEFAULT NULL
                                    COMMENT '工作表、单元格、字段映射及样式设置',
                                status TINYINT NOT NULL DEFAULT 1,
                                created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                CONSTRAINT fk_template_version
                                    FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                                UNIQUE KEY uk_template_type (rule_version_id, template_type)
) ENGINE=InnoDB COMMENT='Excel模板';

-- 10. 学生个人综测任务（临时）
CREATE TABLE assessment_task (
                                 id CHAR(36) PRIMARY KEY COMMENT 'UUID',
                                 college_id BIGINT NOT NULL,
                                 rule_version_id BIGINT NOT NULL,
                                 academic_year VARCHAR(16) NOT NULL,
                                 status VARCHAR(32) NOT NULL DEFAULT 'UPLOADING'
                                     COMMENT 'UPLOADING/PARSING/REVIEWING/CALCULATING/RESULT_REVIEW/CONFIRMED/EXPORTED/EXPIRED/FAILED',
                                 student_name VARCHAR(100) DEFAULT NULL COMMENT '临时数据，可选',
                                 student_no VARCHAR(64) DEFAULT NULL COMMENT '临时数据，可选',
                                 class_code VARCHAR(64) DEFAULT NULL COMMENT '临时数据，可选',
                                 input_data JSON DEFAULT NULL COMMENT '绩点、体测、互评等输入的临时值',
                                 expires_at DATETIME NOT NULL,
                                 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                     ON UPDATE CURRENT_TIMESTAMP,
                                 CONSTRAINT fk_task_college
                                     FOREIGN KEY (college_id) REFERENCES college(id),
                                 CONSTRAINT fk_task_rule_version
                                     FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                                 KEY idx_task_status_expiry (status, expires_at),
                                 KEY idx_task_rule (rule_version_id)
) ENGINE=InnoDB COMMENT='学生个人综测临时任务';

-- 11. 上传材料
CREATE TABLE material_item (
                               id CHAR(36) PRIMARY KEY COMMENT 'UUID',
                               task_id CHAR(36) NOT NULL,
                               requirement_id BIGINT DEFAULT NULL,
                               competition_id BIGINT DEFAULT NULL,
                               category VARCHAR(20) NOT NULL,
                               material_type VARCHAR(100) DEFAULT NULL,
                               original_name VARCHAR(255) NOT NULL,
                               display_name VARCHAR(255) DEFAULT NULL,
                               mime_type VARCHAR(100) DEFAULT NULL,
                               file_size BIGINT DEFAULT NULL,
                               file_hash CHAR(64) DEFAULT NULL,
                               temp_path VARCHAR(500) DEFAULT NULL,
                               extracted_data JSON DEFAULT NULL COMMENT 'AI提取的候选信息',
                               review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                                   COMMENT 'PENDING/AI_SUGGESTED/CONFIRMED/REJECTED',
                               created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               CONSTRAINT fk_material_task
                                   FOREIGN KEY (task_id) REFERENCES assessment_task(id),
                               CONSTRAINT fk_material_requirement
                                   FOREIGN KEY (requirement_id) REFERENCES material_requirement(id),
                               CONSTRAINT fk_material_competition
                                   FOREIGN KEY (competition_id) REFERENCES competition(id),
                               KEY idx_material_task_category (task_id, category),
                               KEY idx_material_status (task_id, review_status)
) ENGINE=InnoDB COMMENT='综测任务材料元数据';

-- 12. 材料操作记录
CREATE TABLE material_operation (
                                    id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                    material_id CHAR(36) NOT NULL,
                                    operation_type VARCHAR(32) NOT NULL
                                        COMMENT 'CROP/COMBINE/RENAME/CLASSIFY/MOVE',
                                    parameters JSON DEFAULT NULL COMMENT '裁剪坐标、拼图顺序、目标名称等',
                                    input_path VARCHAR(500) DEFAULT NULL,
                                    output_path VARCHAR(500) DEFAULT NULL,
                                    status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                                        COMMENT 'PENDING/CONFIRMED/PROCESSING/SUCCESS/FAILED',
                                    error_message TEXT DEFAULT NULL,
                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        ON UPDATE CURRENT_TIMESTAMP,
                                    CONSTRAINT fk_operation_material
                                        FOREIGN KEY (material_id) REFERENCES material_item(id),
                                    KEY idx_operation_material (material_id, status)
) ENGINE=InnoDB COMMENT='材料处理操作记录';

-- 13. 计算结果
CREATE TABLE score_calculation (
                                   id BIGINT PRIMARY KEY AUTO_INCREMENT,
                                   task_id CHAR(36) NOT NULL,
                                   rule_version_id BIGINT NOT NULL,
                                   calculation_no INT NOT NULL DEFAULT 1,
                                   status VARCHAR(32) NOT NULL DEFAULT 'CALCULATED'
                                       COMMENT 'CALCULATED/REVIEWING/CONFIRMED/INVALIDATED',
                                   moral_score DECIMAL(10,4) DEFAULT NULL,
                                   intellectual_score DECIMAL(10,4) DEFAULT NULL,
                                   physical_score DECIMAL(10,4) DEFAULT NULL,
                                   aesthetic_score DECIMAL(10,4) DEFAULT NULL,
                                   labor_score DECIMAL(10,4) DEFAULT NULL,
                                   total_score DECIMAL(10,4) DEFAULT NULL,
                                   input_snapshot JSON DEFAULT NULL COMMENT '本次计算所用输入快照',
                                   calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                   CONSTRAINT fk_calculation_task
                                       FOREIGN KEY (task_id) REFERENCES assessment_task(id),
                                   CONSTRAINT fk_calculation_rule
                                       FOREIGN KEY (rule_version_id) REFERENCES rule_version(id),
                                   UNIQUE KEY uk_task_calculation_no (task_id, calculation_no),
                                   KEY idx_calculation_task (task_id, status)
) ENGINE=InnoDB COMMENT='综测计算结果';

-- 14. 得分明细
CREATE TABLE score_detail (
                              id BIGINT PRIMARY KEY AUTO_INCREMENT,
                              calculation_id BIGINT NOT NULL,
                              bonus_rule_id BIGINT DEFAULT NULL,
                              category VARCHAR(20) NOT NULL,
                              item_name VARCHAR(255) NOT NULL,
                              item_type VARCHAR(20) NOT NULL COMMENT 'BASE/BONUS/DEDUCTION',
                              quantity DECIMAL(10,4) DEFAULT 1,
                              unit VARCHAR(40) DEFAULT NULL,
                              unit_score DECIMAL(10,4) DEFAULT NULL,
                              final_score DECIMAL(10,4) NOT NULL DEFAULT 0,
                              review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                                  COMMENT 'PENDING/CONFIRMED/REJECTED',
                              review_note VARCHAR(500) DEFAULT NULL,
                              rule_snapshot JSON DEFAULT NULL COMMENT '本次适用规则快照',
                              created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                              CONSTRAINT fk_detail_calculation
                                  FOREIGN KEY (calculation_id) REFERENCES score_calculation(id),
                              CONSTRAINT fk_detail_bonus_rule
                                  FOREIGN KEY (bonus_rule_id) REFERENCES bonus_rule(id),
                              KEY idx_detail_calculation (calculation_id, category),
                              KEY idx_detail_bonus_rule (bonus_rule_id)
) ENGINE=InnoDB COMMENT='分项得分明细';

-- 15. 得分与材料关联
CREATE TABLE score_material_ref (
                                    score_detail_id BIGINT NOT NULL,
                                    material_id CHAR(36) NOT NULL,
                                    relation_type VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE'
                                        COMMENT 'EVIDENCE/SUPPLEMENT',
                                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    PRIMARY KEY (score_detail_id, material_id),
                                    CONSTRAINT fk_ref_score_detail
                                        FOREIGN KEY (score_detail_id) REFERENCES score_detail(id),
                                    CONSTRAINT fk_ref_material
                                        FOREIGN KEY (material_id) REFERENCES material_item(id),
                                    KEY idx_ref_material (material_id)
) ENGINE=InnoDB COMMENT='得分与证明材料关联';

-- 16. 导出任务
CREATE TABLE export_task (
                             id BIGINT PRIMARY KEY AUTO_INCREMENT,
                             task_id CHAR(36) NOT NULL,
                             calculation_id BIGINT DEFAULT NULL,
                             status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                                 COMMENT 'PENDING/PROCESSING/SUCCESS/FAILED/EXPIRED',
                             export_type VARCHAR(32) NOT NULL DEFAULT 'PERSONAL'
                                 COMMENT 'PERSONAL',
                             file_name VARCHAR(255) DEFAULT NULL,
                             temp_path VARCHAR(500) DEFAULT NULL,
                             file_size BIGINT DEFAULT NULL,
                             expires_at DATETIME NOT NULL,
                             error_message TEXT DEFAULT NULL,
                             created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             completed_at DATETIME DEFAULT NULL,
                             CONSTRAINT fk_export_task
                                 FOREIGN KEY (task_id) REFERENCES assessment_task(id),
                             CONSTRAINT fk_export_calculation
                                 FOREIGN KEY (calculation_id) REFERENCES score_calculation(id),
                             KEY idx_export_status_expiry (status, expires_at)
) ENGINE=InnoDB COMMENT='个人综测导出任务';
