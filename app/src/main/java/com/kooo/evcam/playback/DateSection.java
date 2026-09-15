package com.kooo.evcam.playback;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 日期分组模型
 * 将同一天的视频/图片组聚合在一起
 * @param <T> VideoGroup 或 PhotoGroup
 */
public class DateSection<T> {
    
    /** 日期字符串，格式为 yyyy-MM-dd */
    private final String dateString;
    
    /** 日期对象 */
    private final Date date;
    
    /** 该日期下的所有组 */
    private final List<T> items;
    
    /** 是否展开 */
    private boolean expanded;
    
    public DateSection(String dateString, Date date) {
        this.dateString = dateString;
        this.date = date;
        this.items = new ArrayList<>();
        this.expanded = isToday(date); // 只有今天默认展开
    }
    
    /**
     * 判断指定日期是否是今天
     */
    private boolean isToday(Date date) {
        Calendar today = Calendar.getInstance();
        Calendar targetDate = Calendar.getInstance();
        targetDate.setTime(date);
        
        return today.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR)
                && today.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR);
    }
    
    /**
     * 添加一个组到此日期
     */
    public void addItem(T item) {
        items.add(item);
    }
    
    /**
     * 获取日期字符串
     */
    public String getDateString() {
        return dateString;
    }
    
    /**
     * 获取日期对象
     */
    public Date getDate() {
        return date;
    }
    
    /**
     * 获取该日期下的所有组
     */
    public List<T> getItems() {
        return items;
    }
    
    /**
     * 获取组数量
     */
    public int getItemCount() {
        return items.size();
    }
    
    /**
     * 是否展开
     */
    public boolean isExpanded() {
        return expanded;
    }
    
    /**
     * 设置展开状态
     */
    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }
    
    /**
     * 切换展开/收起状态
     */
    public void toggleExpanded() {
        this.expanded = !this.expanded;
    }
    
    /**
     * 获取格式化的日期显示字符串
     * 今天显示"Today"，昨天显示"Yesterday"，其他显示日期
     */
    public String getFormattedDateDisplay() {
        Calendar today = Calendar.getInstance();
        Calendar targetDate = Calendar.getInstance();
        targetDate.setTime(date);
        
        // 清除时间部分，只比较日期
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        today.set(Calendar.MILLISECOND, 0);
        
        targetDate.set(Calendar.HOUR_OF_DAY, 0);
        targetDate.set(Calendar.MINUTE, 0);
        targetDate.set(Calendar.SECOND, 0);
        targetDate.set(Calendar.MILLISECOND, 0);
        
        long diffInDays = (today.getTimeInMillis() - targetDate.getTimeInMillis()) / (24 * 60 * 60 * 1000);
        
        if (diffInDays == 0) {
            return "Today";
        } else if (diffInDays == 1) {
            return "Yesterday";
        } else if (diffInDays == 2) {
            return "Earlier";
        } else {
            // 判断是否是同一年
            if (today.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR)) {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd", Locale.CHINESE);
                return sdf.format(date);
            } else {
                SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.CHINESE);
                return sdf.format(date);
            }
        }
    }
    
    /**
     * 获取星期几
     */
    public String getDayOfWeek() {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE", Locale.CHINESE);
        return sdf.format(date);
    }
    
    /**
     * 获取带星期的完整日期显示
     */
    public String getFullDateDisplay() {
        String dateDisplay = getFormattedDateDisplay();
        String dayOfWeek = getDayOfWeek();
        
        // 今天、昨天、前天显示时带上星期
        if ("Today".equals(dateDisplay) || "Yesterday".equals(dateDisplay) || "Earlier".equals(dateDisplay)) {
            return dateDisplay + " · " + dayOfWeek;
        }
        
        return dateDisplay + " " + dayOfWeek;
    }
}
