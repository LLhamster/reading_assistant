package com.example.httpreading.evaluation;

interface EvaluationJudge {
    enum Mode {
        FAST(1), STRICT(3);

        private final int passes;

        Mode(int passes) {
            this.passes = passes;
        }

        int passes() {
            return passes;
        }
    }

    EvaluationMetrics.JudgeScore judge(EvaluationCases.EvaluationExample example,
                                       EvaluationMetrics.AnswerPrediction prediction,
                                       EvaluationMetrics.RuleScore rules,
                                       Mode mode);
}
