#!/system/bin/sh
#
# EVCam 保活配置添加脚本（内置版）
# 用途：将 com.kooo.evcam.v2 添加到车机系统的服务启动列表和后台白名单
# 从应用内通过 su 执行
#
# 基于 E5车友 原始脚本 v2.0
#

# ==================== 配置区域 ====================
PACKAGE_NAME="com.kooo.evcam.v2"
ACTION_NAME="com.kooo.evcam.v2.action.KEEP_ALIVE"
EXPECTED_DEVICE="e245"

# 文件路径
LIFECTL_FILE="/system/etc/geely_lifectl_start_list.xml"
WHITELIST_FILE="/system/etc/ecarx_str_policies.xml"
BGMS_FILE="/vendor/etc/bgms_config.xml"

# 备份目录：E245 使用用户 11，ADB shell 下 /sdcard/ 指向用户 0
# 使用 /data/media/11/ 确保备份文件对用户 11 可见
BACKUP_DIR="/data/media/11/evcam_backup"

# ==================== 工具函数 ====================

print_info() {
    echo "[INFO] $1"
}

print_success() {
    echo "[OK] $1"
}

print_warning() {
    echo "[WARN] $1"
}

print_error() {
    echo "[ERROR] $1"
}

print_step() {
    echo ""
    echo "===== $1 ====="
}

# ==================== 步骤 1：检查设备型号 ====================

check_device_model() {
    print_step "步骤 1/5：检查设备型号"
    
    # 获取设备型号信息
    DEVICE_PRODUCT=$(getprop ro.product.device 2>/dev/null)
    DEVICE_MODEL=$(getprop ro.product.model 2>/dev/null)
    DEVICE_NAME=$(getprop ro.product.name 2>/dev/null)
    BUILD_PRODUCT=$(getprop ro.build.product 2>/dev/null)
    
    print_info "设备信息："
    print_info "  ro.product.device: $DEVICE_PRODUCT"
    print_info "  ro.product.model: $DEVICE_MODEL"
    print_info "  ro.product.name: $DEVICE_NAME"
    print_info "  ro.build.product: $BUILD_PRODUCT"
    
    # 检查是否包含 e245
    if echo "$DEVICE_PRODUCT $DEVICE_MODEL $DEVICE_NAME $BUILD_PRODUCT" | grep -qi "$EXPECTED_DEVICE"; then
        print_success "设备型号匹配：检测到 E245 设备"
        return 0
    else
        print_error "设备型号不匹配！"
        print_error "期望：包含 '$EXPECTED_DEVICE'"
        print_error "实际：$DEVICE_PRODUCT / $DEVICE_MODEL / $DEVICE_NAME"
        return 1
    fi
}

# ==================== 步骤 2：检查写入权限 ====================

check_write_permission() {
    print_step "步骤 2/5：检查 system 和 vendor 分区写入权限"
    
    # 检查是否具有系统权限
    CURRENT_USER=$(id -u 2>/dev/null)
    if [ "$CURRENT_USER" != "0" ]; then
        print_error "当前不具有系统写入权限 (uid=$CURRENT_USER)"
        print_error "请确保设备已开启 USB 调试"
        return 1
    fi
    print_success "已获取系统权限 (uid=0)"
    
    # 将 SELinux 设为宽容模式，避免 "inaccessible or not found" 错误
    SELINUX_STATUS=$(getenforce 2>/dev/null)
    print_info "当前 SELinux 状态: $SELINUX_STATUS"
    if [ "$SELINUX_STATUS" = "Enforcing" ]; then
        setenforce 0 2>/dev/null
        NEW_STATUS=$(getenforce 2>/dev/null)
        if [ "$NEW_STATUS" = "Permissive" ]; then
            print_success "SELinux 已设为 Permissive（宽容模式）"
        else
            print_warning "SELinux 设置失败，后续操作可能受 SELinux 拦截"
        fi
    else
        print_info "SELinux 已处于非 Enforcing 状态，无需修改"
    fi
    
    # 尝试 remount system 分区
    print_info "尝试 remount system/vendor 分区..."
    if [ -x /system/bin/remount ]; then
        print_info "调用 /system/bin/remount ..."
        /system/bin/remount 2>/dev/null
    fi

    mount -o remount,rw / 2>/dev/null
    mount -o remount,rw /system 2>/dev/null

    # 测试 system 写入
    TEST_FILE="/system/.evcam_write_test_$$"
    if touch "$TEST_FILE" 2>/dev/null; then
        rm -f "$TEST_FILE" 2>/dev/null
        print_success "system 分区可写"
    else
        print_error "system 分区不可写！"
        print_error "请先在 PC 端执行以下命令后再重试："
        print_error "  adb root && adb remount"
        return 1
    fi

    mount -o remount,rw /vendor 2>/dev/null

    # 测试 vendor 写入
    TEST_FILE="/vendor/.evcam_write_test_$$"
    if touch "$TEST_FILE" 2>/dev/null; then
        rm -f "$TEST_FILE" 2>/dev/null
        print_success "vendor 分区可写"
    else
        print_error "vendor 分区不可写！"
        print_error "请先在 PC 端执行以下命令后再重试："
        print_error "  adb root && adb remount"
        return 1
    fi
    
    return 0
}

# ==================== 步骤 3：检查文件格式 ====================

check_file_format() {
    print_step "步骤 3/5：检查配置文件格式"
    
    ALL_EXIST=1  # 假设所有配置都已存在
    
    # ========== 检查 geely_lifectl_start_list.xml ==========
    print_info "检查 $LIFECTL_FILE ..."
    
    if [ ! -f "$LIFECTL_FILE" ]; then
        print_error "文件不存在：$LIFECTL_FILE"
        return 1
    fi
    
    if ! grep -q "<services>" "$LIFECTL_FILE"; then
        print_error "文件格式异常：未找到 <services> 标签"
        return 1
    fi
    
    if ! grep -q "</services>" "$LIFECTL_FILE"; then
        print_error "文件格式异常：未找到 </services> 标签"
        return 1
    fi
    
    if grep -q "$PACKAGE_NAME" "$LIFECTL_FILE"; then
        print_warning "[$LIFECTL_FILE] 已包含 $PACKAGE_NAME，将跳过"
        LIFECTL_ALREADY_EXISTS=1
    else
        print_success "[$LIFECTL_FILE] 格式正确，可以添加"
        LIFECTL_ALREADY_EXISTS=0
        ALL_EXIST=0
    fi
    
    # ========== 检查 ecarx_str_policies.xml ==========
    print_info "检查 $WHITELIST_FILE ..."
    
    if [ ! -f "$WHITELIST_FILE" ]; then
        print_error "文件不存在：$WHITELIST_FILE"
        return 1
    fi
    
    if ! grep -q "<whitelist>" "$WHITELIST_FILE"; then
        print_error "文件格式异常：未找到 <whitelist> 标签"
        return 1
    fi
    
    if ! grep -q "</whitelist>" "$WHITELIST_FILE"; then
        print_error "文件格式异常：未找到 </whitelist> 标签"
        return 1
    fi
    
    if grep -q "$PACKAGE_NAME" "$WHITELIST_FILE"; then
        print_warning "[$WHITELIST_FILE] 已包含 $PACKAGE_NAME，将跳过"
        WHITELIST_ALREADY_EXISTS=1
    else
        print_success "[$WHITELIST_FILE] 格式正确，可以添加"
        WHITELIST_ALREADY_EXISTS=0
        ALL_EXIST=0
    fi
    
    # ========== 检查 bgms_config.xml ==========
    print_info "检查 $BGMS_FILE ..."
    
    if [ ! -f "$BGMS_FILE" ]; then
        print_error "文件不存在：$BGMS_FILE"
        return 1
    fi
    
    if ! grep -q '<array name="whitelist">' "$BGMS_FILE"; then
        print_error "文件格式异常：未找到 whitelist 数组"
        return 1
    fi
    
    if ! grep -q '<array name="strwhitelist">' "$BGMS_FILE"; then
        print_error "文件格式异常：未找到 strwhitelist 数组"
        return 1
    fi
    
    if grep -q "$PACKAGE_NAME" "$BGMS_FILE"; then
        print_warning "[$BGMS_FILE] 已包含 $PACKAGE_NAME，将跳过"
        BGMS_ALREADY_EXISTS=1
    else
        print_success "[$BGMS_FILE] 格式正确，可以添加"
        BGMS_ALREADY_EXISTS=0
        ALL_EXIST=0
    fi
    
    if [ "$ALL_EXIST" = "1" ]; then
        print_warning "所有文件都已包含 $PACKAGE_NAME 配置"
        print_warning "无需修改，脚本将退出"
        return 2
    fi
    
    return 0
}

# ==================== 步骤 4：备份并修改文件 ====================

modify_files() {
    print_step "步骤 4/5：备份并修改配置文件"
    
    TIMESTAMP=$(date +%Y%m%d_%H%M%S)
    
    # 创建备份目录
    if [ ! -d "$BACKUP_DIR" ]; then
        mkdir -p "$BACKUP_DIR"
        if [ $? -ne 0 ]; then
            print_error "创建备份目录失败：$BACKUP_DIR"
            return 1
        fi
    fi
    print_info "备份目录：$BACKUP_DIR"
    
    # ========== 修改 geely_lifectl_start_list.xml ==========
    if [ "$LIFECTL_ALREADY_EXISTS" = "0" ]; then
        print_info "备份 $LIFECTL_FILE ..."
        LIFECTL_BACKUP="${BACKUP_DIR}/geely_lifectl_start_list.xml.bak.${TIMESTAMP}"
        cp "$LIFECTL_FILE" "$LIFECTL_BACKUP"
        
        if [ $? -ne 0 ]; then
            print_error "备份失败！"
            return 1
        fi
        print_success "已备份到 $LIFECTL_BACKUP"
        
        print_info "添加 EVCam 服务配置..."
        
        TEMP_FILE="${LIFECTL_FILE}.tmp"
        
        awk '
        /<\/services>/ {
            print "    <!-- EVCam 保活配置 -->"
            print "    <service name=\"com.kooo.evcam.v2\">"
            print "        <action>com.kooo.evcam.v2.action.KEEP_ALIVE</action>"
            print "        <stage>2</stage>"
            print "        <user>10</user>"
            print "        <sticky>true</sticky>"
            print "    </service>"
        }
        { print }
        ' "$LIFECTL_FILE" > "$TEMP_FILE"
        
        if [ $? -eq 0 ] && [ -s "$TEMP_FILE" ] && grep -q '<services>' "$TEMP_FILE" && grep -q '</services>' "$TEMP_FILE" && grep -q "$PACKAGE_NAME" "$TEMP_FILE"; then
            mv "$TEMP_FILE" "$LIFECTL_FILE"
            chmod 644 "$LIFECTL_FILE"
            sync
            print_success "已修改 $LIFECTL_FILE"
        else
            print_error "修改 $LIFECTL_FILE 失败！输出文件验证未通过"
            rm -f "$TEMP_FILE"
            cp "$LIFECTL_BACKUP" "$LIFECTL_FILE"
            sync
            return 1
        fi
    else
        print_info "跳过 $LIFECTL_FILE（已存在配置）"
    fi
    
    # ========== 修改 ecarx_str_policies.xml ==========
    if [ "$WHITELIST_ALREADY_EXISTS" = "0" ]; then
        print_info "备份 $WHITELIST_FILE ..."
        WHITELIST_BACKUP="${BACKUP_DIR}/ecarx_str_policies.xml.bak.${TIMESTAMP}"
        cp "$WHITELIST_FILE" "$WHITELIST_BACKUP"
        
        if [ $? -ne 0 ]; then
            print_error "备份失败！"
            return 1
        fi
        print_success "已备份到 $WHITELIST_BACKUP"
        
        print_info "添加 EVCam 到白名单..."
        
        TEMP_FILE="${WHITELIST_FILE}.tmp"
        
        awk '
        /<\/whitelist>/ {
            print "            <!-- EVCam 保活配置 -->"
            print "            <item package=\"com.kooo.evcam.v2\" />"
        }
        { print }
        ' "$WHITELIST_FILE" > "$TEMP_FILE"
        
        if [ $? -eq 0 ] && [ -s "$TEMP_FILE" ] && grep -q '<whitelist>' "$TEMP_FILE" && grep -q '</whitelist>' "$TEMP_FILE" && grep -q "$PACKAGE_NAME" "$TEMP_FILE"; then
            mv "$TEMP_FILE" "$WHITELIST_FILE"
            chmod 644 "$WHITELIST_FILE"
            sync
            print_success "已修改 $WHITELIST_FILE"
        else
            print_error "修改 $WHITELIST_FILE 失败！输出文件验证未通过"
            rm -f "$TEMP_FILE"
            cp "$WHITELIST_BACKUP" "$WHITELIST_FILE"
            sync
            return 1
        fi
    else
        print_info "跳过 $WHITELIST_FILE（已存在配置）"
    fi
    
    # ========== 修改 bgms_config.xml ==========
    if [ "$BGMS_ALREADY_EXISTS" = "0" ]; then
        print_info "备份 $BGMS_FILE ..."
        BGMS_BACKUP="${BACKUP_DIR}/bgms_config.xml.bak.${TIMESTAMP}"
        cp "$BGMS_FILE" "$BGMS_BACKUP"

        if [ $? -ne 0 ]; then
            print_error "备份失败！"
            return 1
        fi
        print_success "已备份到 $BGMS_BACKUP"

        print_info "添加 EVCam 到 BGMS 白名单（3个位置）..."

        # 使用 cp + 逐个 sed 行号插入，避免 toybox awk 多 flag 状态追踪导致输出异常
        TEMP_FILE="${BGMS_FILE}.tmp"
        cp "$BGMS_FILE" "$TEMP_FILE"
        BGMS_MODIFY_OK=1

        for ARRAY_NAME in "whitelist" "active_throttle_whitelist" "strwhitelist"; do
            # 找到 <array name="ARRAYNAME"> 的行号
            OPEN_LINE=$(grep -n "<array name=\"${ARRAY_NAME}\">" "$TEMP_FILE" | head -1 | cut -d: -f1)
            if [ -z "$OPEN_LINE" ]; then
                print_warning "未找到 <array name=\"${ARRAY_NAME}\">，跳过"
                continue
            fi

            # 找到该开标签之后的第一个 </array> 行号
            CLOSE_LINE=$(tail -n +"$OPEN_LINE" "$TEMP_FILE" | grep -n '</array>' | head -1 | cut -d: -f1)
            if [ -z "$CLOSE_LINE" ]; then
                print_warning "未找到 ${ARRAY_NAME} 的 </array>，跳过"
                continue
            fi
            # CLOSE_LINE 是相对于 OPEN_LINE 的偏移，转为绝对行号
            CLOSE_LINE=$((OPEN_LINE + CLOSE_LINE - 1))

            # 在 </array> 行之前插入 EVCam 条目
            TEMP_FILE2="${TEMP_FILE}.2"
            sed "${CLOSE_LINE}i\\
    <!-- EVCam 保活配置 -->\\
    <pkg>com.kooo.evcam.v2</pkg>" "$TEMP_FILE" > "$TEMP_FILE2"

            if [ $? -ne 0 ] || [ ! -s "$TEMP_FILE2" ]; then
                print_error "sed 插入 ${ARRAY_NAME} 失败"
                rm -f "$TEMP_FILE2"
                BGMS_MODIFY_OK=0
                break
            fi

            mv "$TEMP_FILE2" "$TEMP_FILE"
            print_info "  已插入到 ${ARRAY_NAME}"
        done

        # 综合验证
        if [ "$BGMS_MODIFY_OK" = "1" ]; then
            # 验证输出文件包含有效 XML 内容（非 NULL 字节填充）
            if ! grep -q '<device' "$TEMP_FILE"; then
                print_error "输出文件不包含有效 XML（可能为 NULL 字节填充）"
                BGMS_MODIFY_OK=0
            fi
            # 验证根元素闭合
            if ! grep -q '</device>' "$TEMP_FILE"; then
                print_error "输出文件缺少 </device> 闭合标签"
                BGMS_MODIFY_OK=0
            fi
            # 验证关键系统条目未丢失（全景影像）
            if ! grep -q 'com.geely.avm_app' "$TEMP_FILE"; then
                print_error "关键条目 com.geely.avm_app 丢失！中止操作以保护全景影像功能"
                BGMS_MODIFY_OK=0
            fi
            # 验证 EVCam 条目已添加
            EVCAM_COUNT=$(grep -c "$PACKAGE_NAME" "$TEMP_FILE")
            if [ "$EVCAM_COUNT" -lt 3 ]; then
                print_warning "EVCam 条目仅添加了 ${EVCAM_COUNT} 处（预期 3 处）"
            fi
            # 验证文件大小合理（修改后应比原文件大）
            ORIG_SIZE=$(wc -c < "$BGMS_FILE" | tr -d ' ')
            NEW_SIZE=$(wc -c < "$TEMP_FILE" | tr -d ' ')
            if [ "$NEW_SIZE" -lt "$ORIG_SIZE" ]; then
                print_error "输出文件 (${NEW_SIZE}B) 小于原文件 (${ORIG_SIZE}B)，数据可能损坏"
                BGMS_MODIFY_OK=0
            fi
        fi

        if [ "$BGMS_MODIFY_OK" = "1" ]; then
            mv "$TEMP_FILE" "$BGMS_FILE"
            chmod 644 "$BGMS_FILE"
            sync
            print_success "已修改 $BGMS_FILE"
        else
            print_error "修改 $BGMS_FILE 失败！正在恢复备份..."
            rm -f "$TEMP_FILE" "${TEMP_FILE}.2"
            cp "$BGMS_BACKUP" "$BGMS_FILE"
            chmod 644 "$BGMS_FILE"
            sync
            return 1
        fi
    else
        print_info "跳过 $BGMS_FILE（已存在配置）"
    fi

    sync
    return 0
}

# ==================== 步骤 5：验证修改结果 ====================

verify_modifications() {
    print_step "步骤 5/5：验证修改结果"
    
    VERIFY_FAILED=0
    
    # ========== 验证 geely_lifectl_start_list.xml ==========
    print_info "验证 $LIFECTL_FILE ..."
    
    if ! grep -q "<services>" "$LIFECTL_FILE" || ! grep -q "</services>" "$LIFECTL_FILE"; then
        print_error "XML 结构损坏：缺少 <services> 标签"
        VERIFY_FAILED=1
    fi
    
    if grep -q "$PACKAGE_NAME" "$LIFECTL_FILE"; then
        print_success "已确认包含 $PACKAGE_NAME"
    else
        print_error "未找到 $PACKAGE_NAME，添加可能失败"
        VERIFY_FAILED=1
    fi
    
    OPEN_TAGS=$(grep -o "<service " "$LIFECTL_FILE" | wc -l)
    CLOSE_TAGS=$(grep -o "</service>" "$LIFECTL_FILE" | wc -l)
    if [ "$OPEN_TAGS" != "$CLOSE_TAGS" ]; then
        print_error "XML 标签不配对：<service>=$OPEN_TAGS, </service>=$CLOSE_TAGS"
        VERIFY_FAILED=1
    else
        print_success "XML 标签配对正确"
    fi
    
    echo ""
    
    # ========== 验证 ecarx_str_policies.xml ==========
    print_info "验证 $WHITELIST_FILE ..."
    
    if ! grep -q "<whitelist>" "$WHITELIST_FILE" || ! grep -q "</whitelist>" "$WHITELIST_FILE"; then
        print_error "XML 结构损坏：缺少 <whitelist> 标签"
        VERIFY_FAILED=1
    fi
    
    if grep -q "$PACKAGE_NAME" "$WHITELIST_FILE"; then
        print_success "已确认包含 $PACKAGE_NAME"
    else
        print_error "未找到 $PACKAGE_NAME，添加可能失败"
        VERIFY_FAILED=1
    fi
    
    echo ""
    
    # ========== 验证 bgms_config.xml ==========
    print_info "验证 $BGMS_FILE ..."
    
    BGMS_COUNT=$(grep -c "$PACKAGE_NAME" "$BGMS_FILE")
    if [ "$BGMS_COUNT" -ge 3 ]; then
        print_success "已确认包含 $PACKAGE_NAME（$BGMS_COUNT 处）"
    elif [ "$BGMS_COUNT" -ge 1 ]; then
        print_warning "找到 $PACKAGE_NAME，但只有 $BGMS_COUNT 处（预期 3 处）"
    else
        print_error "未找到 $PACKAGE_NAME，添加可能失败"
        VERIFY_FAILED=1
    fi
    
    echo ""
    
    if [ "$VERIFY_FAILED" = "1" ]; then
        print_error "验证失败！备份文件位于 $BACKUP_DIR/"
        return 1
    fi
    
    return 0
}

# ==================== 主函数 ====================

main() {
    echo ""
    echo "=============================================="
    echo "  EVCam 车机白名单配置脚本"
    echo "  目标包名: $PACKAGE_NAME"
    echo "=============================================="
    echo ""
    echo "将修改以下文件："
    echo "  1. $LIFECTL_FILE (服务启动列表)"
    echo "  2. $WHITELIST_FILE (Ecarx STR白名单)"
    echo "  3. $BGMS_FILE (BGMS后台白名单)"
    echo ""
    
    # 步骤 1：检查设备型号
    check_device_model
    if [ $? -ne 0 ]; then
        exit 1
    fi
    
    # 步骤 2：检查写入权限
    check_write_permission
    if [ $? -ne 0 ]; then
        exit 1
    fi
    
    # 步骤 3：检查文件格式
    check_file_format
    RESULT=$?
    if [ $RESULT -eq 1 ]; then
        exit 1
    elif [ $RESULT -eq 2 ]; then
        exit 0
    fi
    
    # 步骤 4：修改文件
    modify_files
    if [ $? -ne 0 ]; then
        print_error "修改失败！"
        exit 1
    fi
    
    # 步骤 5：验证修改
    verify_modifications
    if [ $? -ne 0 ]; then
        print_error "验证失败！"
        exit 1
    fi
    
    # 完成
    echo ""
    echo "=============================================="
    print_success "配置添加完成！"
    echo "=============================================="
    echo ""
    print_info "已修改的文件："
    print_info "  1. $LIFECTL_FILE"
    print_info "  2. $WHITELIST_FILE"
    print_info "  3. $BGMS_FILE (3个白名单位置)"
    echo ""
    print_info "备份文件位置：$BACKUP_DIR/"
    print_info "请重启车机使配置生效"
    echo ""
}

# 执行主函数
main "$@"
