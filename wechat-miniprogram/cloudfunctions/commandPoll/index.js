// 云函数入口文件 - commandPoll
// 轮询命令：
// 1. 车机端：通过 deviceId 获取待执行命令
// 2. 小程序端：通过 commandId 查询特定命令状态

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const { deviceId, deviceSecret, commandId } = event;
  
  try {
    // 模式1：小程序端查询特定命令状态
    if (commandId) {
      const cmdRes = await db.collection('commands').where({
        commandId: commandId
      }).get();
      
      if (cmdRes.data.length === 0) {
        return {
          success: false,
          message: '命令不存在'
        };
      }
      
      const cmd = cmdRes.data[0];
      
      return {
        success: true,
        command: {
          commandId: cmd.commandId,
          command: cmd.command,
          params: cmd.params,
          status: cmd.status,
          result: cmd.result,
          createTime: cmd.createTime,
          completedTime: cmd.completedTime
        }
      };
    }
    
    // 模式2：车机端轮询待执行命令
    if (!deviceId) {
      return {
        success: false,
        message: '设备ID不能为空'
      };
    }
    
    // 验证设备身份
    const deviceRes = await db.collection('devices').where({
      deviceId: deviceId
    }).get();
    
    if (deviceRes.data.length === 0) {
      return {
        success: false,
        message: '设备不存在'
      };
    }
    
    const device = deviceRes.data[0];
    
    // 可选：验证设备密钥
    if (deviceSecret && device.deviceSecret && device.deviceSecret !== deviceSecret) {
      return {
        success: false,
        message: '设备认证失败'
      };
    }
    
    // 获取待执行的命令
    const commandsRes = await db.collection('commands').where({
      deviceId: deviceId,
      status: 'pending'
    }).orderBy('createTime', 'asc').limit(10).get();
    
    const commands = commandsRes.data;
    
    // 标记命令为执行中
    for (const cmd of commands) {
      await db.collection('commands').doc(cmd._id).update({
        data: {
          status: 'executing',
          updateTime: db.serverDate()
        }
      });
    }
    
    return {
      success: true,
      commands: commands.map(cmd => ({
        commandId: cmd.commandId,
        command: cmd.command,
        params: cmd.params,
        createTime: cmd.createTime
      }))
    };
    
  } catch (err) {
    console.error('轮询命令失败:', err);
    return {
      success: false,
      message: '轮询失败: ' + err.message
    };
  }
};
