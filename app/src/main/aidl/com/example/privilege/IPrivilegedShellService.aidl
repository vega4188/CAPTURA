package com.example.privilege;

interface IPrivilegedShellService {
    String runCommand(String cmd) = 1;
    void destroy() = 16777114;
}
