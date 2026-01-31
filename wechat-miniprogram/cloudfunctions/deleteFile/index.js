// 云函数入口文件 - deleteFile
// 删除文件

const cloud = require('wx-server-sdk');
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

const db = cloud.database();

// 云函数入口函数
exports.main = async (event, context) => {
  const wxContext = cloud.getWXContext();
  const openid = wxContext.OPENID;
  
  const { fileId, recordId } = event;
  
  if (!fileId && !recordId) {
    return {
      success: false,
      message: '文件ID不能为空'
    };
  }
  
  try {
    // 查找文件记录
    let fileRecord;
    
    if (recordId) {
      const recordRes = await db.collection('files').doc(recordId).get();
      fileRecord = recordRes.data;
    } else {
      const recordRes = await db.collection('files').where({
        fileId: fileId
      }).get();
      
      if (recordRes.data.length > 0) {
        fileRecord = recordRes.data[0];
      }
    }
    
    if (!fileRecord) {
      return {
        success: false,
        message: '文件不存在'
      };
    }
    
    // 验证用户权限
    if (fileRecord.userId && fileRecord.userId !== openid) {
      // 检查设备是否属于当前用户
      const deviceRes = await db.collection('devices').where({
        deviceId: fileRecord.deviceId,
        boundUserId: openid
      }).get();
      
      if (deviceRes.data.length === 0) {
        return {
          success: false,
          message: '无权删除此文件'
        };
      }
    }
    
    // 删除云存储中的文件
    const filesToDelete = [];
    if (fileRecord.fileId) {
      filesToDelete.push(fileRecord.fileId);
    }
    if (fileRecord.thumbFileId) {
      filesToDelete.push(fileRecord.thumbFileId);
    }
    
    if (filesToDelete.length > 0) {
      try {
        await cloud.deleteFile({
          fileList: filesToDelete
        });
      } catch (e) {
        console.log('删除云存储文件失败:', e);
        // 继续删除记录
      }
    }
    
    // 删除数据库记录
    await db.collection('files').doc(fileRecord._id).remove();
    
    return {
      success: true,
      message: '文件已删除'
    };
    
  } catch (err) {
    console.error('删除文件失败:', err);
    return {
      success: false,
      message: '删除失败: ' + err.message
    };
  }
};
