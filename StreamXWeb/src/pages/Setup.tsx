import { useState, useCallback, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../services/api.js'
import './Login.css'

export const SetupPage = () => {
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = useCallback(
    async (e: FormEvent) => {
      e.preventDefault()
      setError(null)

      if (!password.trim()) {
        setError('Please enter a password')
        return
      }
      if (password.length < 6) {
        setError('Password must be at least 6 characters')
        return
      }
      if (password !== confirmPassword) {
        setError('Passwords do not match')
        return
      }

      setLoading(true)
      try {
        await api.setupOwnerPassword(password)
        navigate('/', { replace: true })
        window.location.reload()
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Setup failed')
      } finally {
        setLoading(false)
      }
    },
    [password, confirmPassword, navigate],
  )

  return (
    <div className="login-page">
      <div className="login-container">
        <div className="login-logo">
          <svg width="80" height="80" viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path d="M40 0C17.909 0 0 17.909 0 40s17.909 40 40 40 40-17.909 40-40S62.091 0 40 0zm0 73.333C21.591 73.333 6.667 58.409 6.667 40S21.591 6.667 40 6.667 73.333 21.591 73.333 40 58.409 73.333 40 73.333z" fill="#8E8E93"/>
            <path d="M40 20c-11.046 0-20 8.954-20 20s8.954 20 20 20 20-8.954 20-20-8.954-20-20-20z" fill="#8E8E93"/>
          </svg>
        </div>

        <h1 className="login-title">Create Admin Password</h1>
        <p className="login-subtitle">
          Welcome to your self-hosted music streaming server.
          <br />
          Set a password to secure your instance.
        </p>

        <form className="login-form" onSubmit={handleSubmit}>
          <div className="login-input-group">
            <input
              type="password"
              className="login-input"
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={loading}
              autoComplete="new-password"
            />
          </div>

          <div className="login-input-group">
            <input
              type="password"
              className="login-input login-input-password"
              placeholder="Confirm password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
              disabled={loading}
              autoComplete="new-password"
            />
            <button
              type="submit"
              className="login-submit-btn"
              disabled={loading || !password.trim() || !confirmPassword.trim()}
              aria-label="Create password"
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M5 12h14m-7-7l7 7-7 7" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
            </button>
          </div>

          {error && <div className="login-error">{error}</div>}
        </form>
      </div>
    </div>
  )
}
