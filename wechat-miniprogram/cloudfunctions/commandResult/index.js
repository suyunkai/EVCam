// 云函数入口文件 - commandResult
// 车机端上报命令执行结果

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const { deviceId, commandId, success, result, errorMessage } = event;
  
  if (!deviceId || !commandId) {
    return {
      success: false,
      message: '参数不完整'
    };
  }
  
  try {
    // 更新命令状态
    const commandRes = await db.collection('commands').where({
      deviceId: deviceId,
      commandId: commandId
    }).get();
    
    if (commandRes.data.length === 0) {
      return {
        success: false,
        message: '命令不存在'
      };
    }
    
    const command = commandRes.data[0];
    
    await db.collection('commands').doc(command._id).update({
      data: {
        status: success ? 'completed' : 'failed',
        result: result || null,
        errorMessage: errorMessage || null,
        completedTime: db.serverDate(),
        updateTime: db.serverDate()
      }
    });
    
    return {
      success: true,
      message: '结果已上报'
    };
    
  } catch (err) {
    console.error('上报命令结果失败:', err);
    return {
      success: false,
      message: '上报失败: ' + err.message
    };
  }
};
