package com.follarce;

import com.follarce.API.*;
public class Main {
    /**
     * 主方法
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        // 获取当前时间组件数组
        int arr[] = TimeUtil.getTime();
        // 这里可以添加使用时间组件的代码
        for (int i = 0; i < arr.length; i++) {
            System.out.print(arr[i]);
            if (i < arr.length - 1) {
                System.out.print(" ");
            }
            
        }
        System.out.println();
    }
}

