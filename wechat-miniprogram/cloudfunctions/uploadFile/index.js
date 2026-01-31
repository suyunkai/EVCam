// 云函数入口文件 - uploadFile
// 车机端上传文件记录

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const { 
    deviceId, 
    fileId,           // 云存储文件ID
    fileName,         // 文件名
    fileType,         // 文件类型: video/photo
    fileSize,         // 文件大小（字节）
    duration,         // 视频时长（秒）
    thumbFileId,      // 缩略图云存储ID
    commandId         // 关联的命令ID（可选）
  } = event;
  
  if (!deviceId || !fileId || !fileType) {
    return {
      success: false,
      message: '参数不完整'
    };
  }
  
  try {
    // 验证设备存在
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
    
    // 获取文件临时URL
    let tempFileURL = null;
    let thumbUrl = null;
    
    try {
      const urlRes = await cloud.getTempFileURL({
        fileList: [fileId]
      });
      if (urlRes.fileList && urlRes.fileList[0]) {
        tempFileURL = urlRes.fileList[0].tempFileURL;
      }
      
      if (thumbFileId) {
        const thumbUrlRes = await cloud.getTempFileURL({
          fileList: [thumbFileId]
        });
        if (thumbUrlRes.fileList && thumbUrlRes.fileList[0]) {
          thumbUrl = thumbUrlRes.fileList[0].tempFileURL;
        }
      }
    } catch (e) {
      console.log('获取临时URL失败，继续保存记录');
    }
    
    // 创建文件记录
    const fileRecord = {
      deviceId: deviceId,
      userId: device.boundUserId || null,
      fileId: fileId,
      fileName: fileName || `${fileType}_${Date.now()}`,
      fileType: fileType,
      fileSize: fileSize || 0,
      duration: duration || null,
      thumbFileId: thumbFileId || null,
      thumbUrl: thumbUrl,
      tempFileURL: tempFileURL,
      commandId: commandId || null,
      createTime: db.serverDate(),
      updateTime: db.serverDate()
    };
    
    const addRes = await db.collection('files').add({
      data: fileRecord
    });
    
    // 如果有关联命令，更新命令结果
    if (commandId) {
      await db.collection('commands').where({
        commandId: commandId
      }).update({
        data: {
          result: {
            fileId: fileId,
            fileRecordId: addRes._id
          },
          updateTime: db.serverDate()
        }
      });
    }
    
    return {
      success: true,
      message: '文件记录已保存',
      recordId: addRes._id
    };
    
  } catch (err) {
    console.error('上传文件记录失败:', err);
    return {
      success: false,
      message: '保存失败: ' + err.message
    };
  }
};
