package org.jeecg.modules.demo.track;

/**
 * @author wggg
 * @date 2026/3/25 9:00
 * 匈牙利算法：O(n³) 最优二分匹配
 * 输入代价矩阵，输出每行匹配的列（-1表示未匹配）
 */
public class HungarianAlgorithm {

    public static int[] assign(double[][] cost) {
        int n = cost.length;
        if (n == 0) return new int[0];
        int m = cost[0].length;
        int dim = Math.max(n, m);

        // 填充为方阵
        double[][] C = new double[dim][dim];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++)
                C[i][j] = cost[i][j];

        double[] u = new double[dim + 1];
        double[] v = new double[dim + 1];
        int[] p = new int[dim + 1];
        int[] way = new int[dim + 1];

        for (int i = 1; i <= dim; i++) {
            p[0] = i;
            int j0 = 0;
            double[] minVal = new double[dim + 1];
            boolean[] used = new boolean[dim + 1];
            java.util.Arrays.fill(minVal, Double.MAX_VALUE);

            do {
                used[j0] = true;
                int i0 = p[j0], j1 = -1;
                double delta = Double.MAX_VALUE;
                for (int j = 1; j <= dim; j++) {
                    if (!used[j]) {
                        double cur = C[i0 - 1][j - 1] - u[i0] - v[j];
                        if (cur < minVal[j]) {
                            minVal[j] = cur;
                            way[j] = j0;
                        }
                        if (minVal[j] < delta) {
                            delta = minVal[j];
                            j1 = j;
                        }
                    }
                }
                for (int j = 0; j <= dim; j++) {
                    if (used[j]) {
                        u[p[j]] += delta;
                        v[j] -= delta;
                    } else {
                        minVal[j] -= delta;
                    }
                }
                j0 = j1;
            } while (p[j0] != 0);

            do {
                int j1 = way[j0];
                p[j0] = p[j1];
                j0 = j1;
            } while (j0 != 0);
        }

        int[] result = new int[n];
        java.util.Arrays.fill(result, -1);
        for (int j = 1; j <= dim; j++) {
            if (p[j] != 0 && p[j] <= n && j <= m) {
                result[p[j] - 1] = j - 1;
            }
        }
        return result;
    }
}
