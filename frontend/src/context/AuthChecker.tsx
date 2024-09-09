import { useContext, useEffect } from "react";
import { UserContext } from "./UserContext";
import { useNavigate } from "react-router-dom";

const AuthChecker = ({ children }: { children: React.ReactNode }) => {
  const { state, checkAuth } = useContext(UserContext);
  const navigate = useNavigate();

  useEffect(() => {
    if (!state.isAuthenticated) {
      checkAuth();
    }
  }, [state.isAuthenticated, checkAuth, navigate]);

  if (!state.isAuthenticated) {
    navigate("/login");
    return null;
  }

  return <>{children}</>;
};

export default AuthChecker;
