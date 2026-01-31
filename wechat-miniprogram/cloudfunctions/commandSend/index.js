// 云函数入口文件 - commandSend
// 发送命令到设备（小程序调用）

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const wxContext = cloud.getWXContext();
  const openid = wxContext.OPENID;
  
  const { deviceId, command, params } = event;
  
  if (!deviceId || !command) {
    return {
      success: false,
      message: '参数不完整'
    };
  }
  
  // 支持的命令列表
  const validCommands = ['record', 'photo', 'start_recording', 'stop_recording', 'status', 'start_preview', 'stop_preview'];
  if (!validCommands.includes(command)) {
    return {
      success: false,
      message: '不支持的命令'
    };
  }
  
  try {
    // 检查设备是否属于当前用户
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId,
      boundUserId: openid
    }).get();
    
    if (deviceRes.data.length === 0) {
      return {
        success: false,
        message: '设备不存在或不属于您'
      };
    }
    
    const device = deviceRes.data[0];
    
    // 检查设备是否在线
    const HEARTBEAT_TIMEOUT = 60 * 1000;
    const lastHeartbeat = device.lastHeartbeat ? new Date(device.lastHeartbeat).getTime() : 0;
    const isOnline = (Date.now() - lastHeartbeat) < HEARTBEAT_TIMEOUT;
    
    if (!isOnline) {
      return {
        success: false,
        message: '设备离线'
      };
    }
    
    // 创建命令记录
    const commandId = `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
    
    await db.collection('commands').add({
      data: {
        commandId: commandId,
        deviceId: deviceId,
        userId: openid,
        command: command,
        params: params || {},
        status: 'pending',  // pending, executing, completed, failed
        createTime: db.serverDate(),
        updateTime: db.serverDate()
      }
    });
    
    return {
      success: true,
      message: '命令已发送',
      commandId: commandId
    };
    
  } catch (err) {
    console.error('发送命令失败:', err);
    return {
      success: false,
      message: '发送失败: ' + err.message
    };
  }
};
