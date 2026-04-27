package org.jeecg.modules.demo.track;

/**
 * 运动预测
 * @author wggg
 * @date 2026/3/25 8:59
 */
/**
 * 状态向量: [x, y, w, h, vx, vy, vw, vh]
 * 观测向量: [x, y, w, h]
 */
public class KalmanFilter {

    private double[] state;   // 8维状态
    private double[][] P;     // 协方差矩阵 8x8
    private double[][] F;     // 状态转移矩阵
    private double[][] H;     // 观测矩阵 4x8
    private double[][] Q;     // 过程噪声
    private double[][] R;     // 观测噪声

    public KalmanFilter(double[] initBbox) {
        // [cx, cy, w, h]
        state = new double[]{
                initBbox[0], initBbox[1], initBbox[2], initBbox[3],
                0, 0, 0, 0
        };
        P = identity8(10.0);

        // 状态转移: 匀速模型
        F = new double[][]{
                {1,0,0,0,1,0,0,0},
                {0,1,0,0,0,1,0,0},
                {0,0,1,0,0,0,1,0},
                {0,0,0,1,0,0,0,1},
                {0,0,0,0,1,0,0,0},
                {0,0,0,0,0,1,0,0},
                {0,0,0,0,0,0,1,0},
                {0,0,0,0,0,0,0,1}
        };

        // 观测矩阵: 只观测位置和尺寸
        H = new double[][]{
                {1,0,0,0,0,0,0,0},
                {0,1,0,0,0,0,0,0},
                {0,0,1,0,0,0,0,0},
                {0,0,0,1,0,0,0,0}
        };

        Q = identity8(1.0);
        R = identity4(10.0);
    }

    /** 预测下一帧位置 */
    public double[] predict() {
        state = matVecMul(F, state);
        P = matAdd(matMul(matMul(F, P), transpose(F)), Q);
        return getBbox();
    }

    /** 用观测值更新状态 */
    public void update(double[] measurement) {
        // y = z - H*x
        double[] y = new double[4];
        double[] hx = matVecMul(H, state);
        for (int i = 0; i < 4; i++) y[i] = measurement[i] - hx[i];

        // S = H*P*H' + R
        double[][] S = matAdd(matMul(matMul(H, P), transpose(H)), R);

        // K = P*H'*S^-1
        double[][] K = matMul(matMul(P, transpose(H)), inverse4(S));

        // x = x + K*y
        double[] Ky = matVecMul(K, y);
        for (int i = 0; i < 8; i++) state[i] += Ky[i];

        // P = (I - K*H)*P
        double[][] KH = matMul(K, H);
        double[][] I_KH = matSub(identity8(1.0), KH);
        P = matMul(I_KH, P);
    }

    public double[] getBbox() {
        return new double[]{state[0], state[1], state[2], state[3]};
    }

    // ---------- 矩阵工具方法 ----------

    private double[] matVecMul(double[][] A, double[] v) {
        int rows = A.length, cols = v.length;
        double[] result = new double[rows];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                result[i] += A[i][j] * v[j];
        return result;
    }

    private double[][] matMul(double[][] A, double[][] B) {
        int m = A.length, n = B[0].length, k = B.length;
        double[][] C = new double[m][n];
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                for (int p = 0; p < k; p++)
                    C[i][j] += A[i][p] * B[p][j];
        return C;
    }

    private double[][] matAdd(double[][] A, double[][] B) {
        double[][] C = new double[A.length][A[0].length];
        for (int i = 0; i < A.length; i++)
            for (int j = 0; j < A[0].length; j++)
                C[i][j] = A[i][j] + B[i][j];
        return C;
    }

    private double[][] matSub(double[][] A, double[][] B) {
        double[][] C = new double[A.length][A[0].length];
        for (int i = 0; i < A.length; i++)
            for (int j = 0; j < A[0].length; j++)
                C[i][j] = A[i][j] - B[i][j];
        return C;
    }

    private double[][] transpose(double[][] A) {
        double[][] T = new double[A[0].length][A.length];
        for (int i = 0; i < A.length; i++)
            for (int j = 0; j < A[0].length; j++)
                T[j][i] = A[i][j];
        return T;
    }

    private double[][] identity8(double scale) {
        double[][] I = new double[8][8];
        for (int i = 0; i < 8; i++) I[i][i] = scale;
        return I;
    }

    private double[][] identity4(double scale) {
        double[][] I = new double[4][4];
        for (int i = 0; i < 4; i++) I[i][i] = scale;
        return I;
    }

    /** 4x4 矩阵求逆（高斯消元） */
    private double[][] inverse4(double[][] mat) {
        int n = 4;
        double[][] a = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) a[i][j] = mat[i][j];
            a[i][i + n] = 1.0;
        }
        for (int i = 0; i < n; i++) {
            double pivot = a[i][i];
            for (int j = 0; j < 2 * n; j++) a[i][j] /= pivot;
            for (int k = 0; k < n; k++) {
                if (k == i) continue;
                double factor = a[k][i];
                for (int j = 0; j < 2 * n; j++) a[k][j] -= factor * a[i][j];
            }
        }
        double[][] inv = new double[n][n];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < n; j++)
                inv[i][j] = a[i][j + n];
        return inv;
    }
}