package com.example.na_honja_ansanda;

import com.example.na_honja_ansanda.data.model.FaceTaskResponse;
import com.google.gson.Gson;
import org.junit.Test;
import static org.junit.Assert.*;

public class FaceTaskResponseTest {
    private FaceTaskResponse task(String json) {
        return new Gson().fromJson(json, FaceTaskResponse.class);
    }

    @Test public void acceptingPhotosDoesNotCompleteTraining() {
        FaceTaskResponse accepted = task("{\"taskId\":\"job-1\",\"status\":\"QUEUED\",\"message\":\"accepted\"}");
        assertTrue(accepted.hasTaskId());
        assertEquals(FaceTaskResponse.State.QUEUED, accepted.getState());
        assertFalse(accepted.isComplete());
        assertTrue(accepted.isPending());
    }

    @Test public void onlySuccessfulTrainingIsComplete() {
        for (String status : new String[]{"RUNNING", "FAILED", "unknown", "", "SUCCESS"}) {
            FaceTaskResponse response = task("{\"taskId\":\"job-1\",\"status\":\"" + status + "\"}");
            assertFalse(status, response.isComplete());
        }
        assertTrue(task("{\"taskId\":\"job-1\",\"status\":\"SUCCEEDED\"}").isComplete());
    }

    @Test public void malformedOrMissingStatusCannotBeSuccess() {
        assertEquals(FaceTaskResponse.State.UNKNOWN, task("{}").getState());
        assertFalse(task("{}").hasTaskId());
        assertFalse(task("{\"taskId\":\"  \"}").hasTaskId());
        assertEquals(FaceTaskResponse.State.UNKNOWN, task("{\"status\":\"COMPLETED\"}").getState());
    }
}
