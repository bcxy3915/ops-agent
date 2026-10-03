package com.example.opsaiagent;

import com.example.opsaiagent.entity.OpsServiceEntity;
import com.example.opsaiagent.service.OpsServiceManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class OpsServiceManagerTest {

    @Autowired
    private OpsServiceManager manager;

    @Test
    void testFindByName() {
        Optional<OpsServiceEntity> result = manager.findByName("todo-service");
        assertTrue(result.isPresent());
        System.out.println(result.get());
    }

    @Test
    void testListAll() {
        List<OpsServiceEntity> list = manager.listAll();
        assertFalse(list.isEmpty());
    }
}